package com.tutor.llm;

import com.tutor.config.LlmProperties;
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
                List.of(SystemMessage.from("system"), UserMessage.from("question")), "trace"))
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
            gateway.chatStream(Purpose.CHAT,
                    List.of(SystemMessage.from("system"), UserMessage.from("question")),
                    "stream-trace", new NoopStreamingHandler(), cancellation);

            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();

            assertThat(disconnected.await(3, TimeUnit.SECONDS)).isTrue();
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
            AtomicReference<FinishReason> finishReason = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            gateway.chatStream(Purpose.CHAT,
                    List.of(SystemMessage.from("system"), UserMessage.from("question")),
                    "stream-trace", new NoopStreamingHandler() {
                        @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                            finishReason.set(response.finishReason());
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
            assertThat(finishReason.get()).isEqualTo(FinishReason.STOP);
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
            AtomicReference<FinishReason> finishReason = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            gateway.chatStream(Purpose.CHAT,
                    List.of(SystemMessage.from("system"), UserMessage.from("question")),
                    "stream-trace", new NoopStreamingHandler() {
                        @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                            finishReason.set(response.finishReason());
                            completed.countDown();
                        }
                    }, new CancellationToken());

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(finishReason.get()).isEqualTo(FinishReason.LENGTH);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer sseServer(AtomicReference<String> requestBody, List<String> events) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
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

    private static class NoopStreamingHandler implements dev.langchain4j.model.chat.response.StreamingChatResponseHandler {
        @Override public void onPartialResponse(String token) { }
        @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) { }
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
