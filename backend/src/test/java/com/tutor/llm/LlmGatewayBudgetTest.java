package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LlmGatewayBudgetTest {
    @Mock
    JdbcTemplate jdbc;
    @Mock
    LlmBudgetGuard budgetGuard;
    @Mock
    LlmConcurrencyGate concurrency;

    @Test
    void rollsBackReservedBudgetWhenConcurrencyAcquireFails() {
        LlmGateway gateway = new LlmGateway(properties(), jdbc, budgetGuard, concurrency);
        doThrow(new IllegalStateException("full")).when(concurrency).acquire();

        assertThatThrownBy(() -> gateway.chatJson(Purpose.EXPERT,
                List.of(SystemMessage.from("system"), UserMessage.from("question")), "trace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("full");

        verify(budgetGuard).settle(eq("trace"), anyLong(), eq(0L));
        verify(concurrency, never()).release();
    }

    @Test
    void cancelsProviderStreamingConnectionWhenRequestIsAborted() throws Exception {
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
            verify(budgetGuard).settle(eq("stream-trace"), anyLong(), anyLong());
            verify(concurrency).release();
        } finally {
            server.stop(0);
        }
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

    private static final class NoopStreamingHandler implements dev.langchain4j.model.chat.response.StreamingChatResponseHandler {
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
