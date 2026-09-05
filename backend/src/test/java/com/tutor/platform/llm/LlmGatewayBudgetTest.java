package com.tutor.platform.llm;

import com.tutor.platform.config.LlmProperties;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.jdbc.core.JdbcTemplate;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.timeout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayBudgetTest {
    @Mock
    JdbcTemplate jdbc;
    @Mock
    LlmBudgetGuard budgetGuard;
    @Mock
    LlmConcurrencyGate concurrency;

    private void stubReservation() {
        when(budgetGuard.reserve(anyString(), anyLong(), anyBoolean())).thenAnswer(invocation ->
                new LlmBudgetGuard.Reservation(invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Long.class),
                        invocation.getArgument(2, Boolean.class), null));
    }

    @Test
    void rollsBackReservedBudgetWhenConcurrencyAcquireFails() {
        stubReservation();
        LlmGateway gateway = new LlmGateway(properties(), jdbc, budgetGuard, concurrency);
        doThrow(new IllegalStateException("full")).when(concurrency).acquire();

        assertThatThrownBy(() -> gateway.chatJson(Purpose.EXPERT,
                List.of(LlmMessage.system("system"), LlmMessage.user("question")), "trace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("full");

        ArgumentCaptor<LlmBudgetGuard.Reservation> reservation =
                ArgumentCaptor.forClass(LlmBudgetGuard.Reservation.class);
        verify(budgetGuard).settle(reservation.capture(), eq(0L));
        assertThat(reservation.getValue().traceId()).isEqualTo("trace");
        verify(concurrency, never()).release();
    }

    @Test
    void cancelsProviderStreamingConnectionWhenRequestIsAborted() throws Exception {
        stubReservation();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        server.createContext("/", exchange -> streamUntilClientDisconnects(exchange, connected, disconnected));
        server.start();

        try {
            LlmGateway gateway = new LlmGateway(properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    jdbc, budgetGuard, concurrency);
            CancellationToken cancellation = new CancellationToken();
            // chatStream 是同步的 (返回即完成)，因此取消必须来自另一个线程 ——
            // 生产中正是如此：cancel 由 SSE 的 onTimeout/onError 回调线程发出。
            CountDownLatch returned = new CountDownLatch(1);
            Thread streaming = new Thread(() -> {
                try {
                    gateway.chatStream(Purpose.CHAT,
                            List.of(LlmMessage.system("system"), LlmMessage.user("question")),
                            "stream-trace", new NoopStreamingHandler(), cancellation);
                } finally {
                    returned.countDown();
                }
            }, "test-chat-stream");
            streaming.start();

            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();

            assertThat(disconnected.await(3, TimeUnit.SECONDS)).isTrue();
            // 取消后 chatStream 必须及时返回，不能把调用线程一直挂在已断开的流上。
            assertThat(returned.await(5, TimeUnit.SECONDS))
                    .as("取消后 chatStream 应当返回").isTrue();
            verify(budgetGuard).settle(any(LlmBudgetGuard.Reservation.class), anyLong());
            verify(concurrency).release();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void settlesStreamingCostWithProviderUsageAndRequestsUsageStream() throws Exception {
        stubReservation();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = sseServer(requestBody, List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好世界\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7}}",
                "[DONE]"));
        server.start();
        try {
            LlmGateway gateway = new LlmGateway(properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    jdbc, budgetGuard, concurrency);
            AtomicReference<Boolean> truncated = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            gateway.chatStream(Purpose.CHAT,
                    List.of(LlmMessage.system("system"), LlmMessage.user("question")),
                    "stream-trace", new NoopStreamingHandler() {
                        @Override public void onComplete(LlmStreamResult response) {
                            truncated.set(response.truncated());
                            completed.countDown();
                        }
                    }, new CancellationToken());

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            // P0 回归: 流式调用的真实成本必须进入结算，而不是把预留全额释放。
            ArgumentCaptor<LlmBudgetGuard.Reservation> reservation =
                    ArgumentCaptor.forClass(LlmBudgetGuard.Reservation.class);
            verify(budgetGuard, timeout(5000)).settle(reservation.capture(), eq(49L));
            assertThat(reservation.getValue().traceId()).isEqualTo("stream-trace");
            assertThat(reservation.getValue().reservedTokens()).isGreaterThan(49L);
            assertThat(truncated.get()).isFalse();
            // 记账精度: 请求显式开启 include_usage，供应商才会在流末尾上报真实用量。
            assertThat(requestBody.get()).contains("\"stream_options\":{\"include_usage\":true}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marksStreamingAnswerTruncatedWhenProviderReportsLengthCutoff() throws Exception {
        stubReservation();
        HttpServer server = sseServer(new AtomicReference<>(), List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"部分回答\"},\"finish_reason\":\"length\"}]}",
                "[DONE]"));
        server.start();
        try {
            LlmGateway gateway = new LlmGateway(properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    jdbc, budgetGuard, concurrency);
            AtomicReference<Boolean> truncated = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            gateway.chatStream(Purpose.CHAT,
                    List.of(LlmMessage.system("system"), LlmMessage.user("question")),
                    "stream-trace", new NoopStreamingHandler() {
                        @Override public void onComplete(LlmStreamResult response) {
                            truncated.set(response.truncated());
                            completed.countDown();
                        }
                    }, new CancellationToken());

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(truncated.get()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    /**
     * chatStream 必须在回答生成完毕后才返回。所有调用方 (ChatService.streamAnswer、
     * Aggregator、ChatTurnService.run) 都按同步语义编写：ChatTurnService 在 turn() 返回
     * 后立刻判定终态，若此时回答还在流，claim 仍是 RUNNING，会被误判成
     * "聊天回合未产生终态" 而置为 FAILED；随后真正完成时 completeWithMessage 的
     * WHERE status='RUNNING' 不再匹配，回答落不了库，onDone 也发不出去，SSE 挂到超时。
     */
    @Test
    void chatStreamDoesNotReturnBeforeTheAnswerIsComplete() throws Exception {
        stubReservation();
        HttpServer server = sseServer(new AtomicReference<>(), List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回答\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]"), 300L);
        server.start();
        try {
            LlmGateway gateway = new LlmGateway(properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    jdbc, budgetGuard, concurrency);
            AtomicBoolean completed = new AtomicBoolean();

            gateway.chatStream(Purpose.CHAT,
                    List.of(LlmMessage.system("system"), LlmMessage.user("question")),
                    "stream-trace", new NoopStreamingHandler() {
                        @Override public void onComplete(LlmStreamResult response) {
                            completed.set(true);
                        }
                    }, new CancellationToken());

            assertThat(completed).as("chatStream 返回时回答必须已完成").isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer sseServer(AtomicReference<String> requestBody, List<String> events) throws IOException {
        return sseServer(requestBody, events, 0L);
    }

    private static HttpServer sseServer(AtomicReference<String> requestBody, List<String> events,
                                        long delayBeforeFirstEventMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            if (delayBeforeFirstEventMs > 0) {
                try {
                    Thread.sleep(delayBeforeFirstEventMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try (OutputStream output = exchange.getResponseBody()) {
                for (String event : events) {
                    output.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        });
        return server;
    }

    private static void streamUntilClientDisconnects(com.sun.net.httpserver.HttpExchange exchange,
                                                     CountDownLatch connected,
                                                     CountDownLatch disconnected) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        String event = "data: {\"id\":\"stream\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n";
        try (OutputStream output = exchange.getResponseBody()) {
            connected.countDown();
            while (true) {
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
                Thread.sleep(20);
            }
        } catch (IOException e) {
            disconnected.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disconnected.countDown();
        }
    }

    private static class NoopStreamingHandler implements LlmStreamHandler {
        @Override public void onToken(String token) { }
        @Override public void onComplete(LlmStreamResult response) { }
        @Override public void onError(Throwable error) { }
    }

    private static LlmProperties properties() {
        return properties("https://api.deepseek.com");
    }

    private static LlmProperties properties(String deepseekBaseUrl) {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", deepseekBaseUrl),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, 25), LlmProperties.TokenLimits.defaults());
    }
}
