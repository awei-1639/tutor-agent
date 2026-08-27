package com.tutor.llm;

import com.sun.net.httpserver.HttpServer;
import com.tutor.config.LlmProperties;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class LlmGatewayFallbackTest {
    @Mock JdbcTemplate jdbc;
    @Mock LlmBudgetGuard budgetGuard;
    @Mock LlmConcurrencyGate concurrency;

    @Test
    void fallsBackToSecondaryProviderWhenPrimaryFails() throws Exception {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer primary = jsonServer(500, "{}", primaryHits);
        HttpServer fallback = jsonServer(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}", fallbackHits);
        try {
            LlmGateway gateway = new LlmGateway(
                    propertiesWithFallback(baseUrl(primary), baseUrl(fallback), true), jdbc, budgetGuard, concurrency);

            String result = gateway.chatJson(Purpose.EXPERT,
                    List.of(SystemMessage.from("system"), UserMessage.from("q")), "trace", null, 1);

            assertThat(result).contains("ok");
            assertThat(primaryHits.get()).isEqualTo(1);
            assertThat(fallbackHits.get()).isEqualTo(1);
        } finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    void doesNotCallFallbackWhenDisabled() throws Exception {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer primary = jsonServer(500, "{}", primaryHits);
        HttpServer fallback = jsonServer(200, "{}", fallbackHits);
        try {
            LlmGateway gateway = new LlmGateway(
                    propertiesWithFallback(baseUrl(primary), baseUrl(fallback), false), jdbc, budgetGuard, concurrency);

            assertThatThrownBy(() -> gateway.chatJson(Purpose.EXPERT,
                    List.of(SystemMessage.from("system"), UserMessage.from("q")), "trace", null, 1))
                    .isInstanceOf(RuntimeException.class);

            assertThat(primaryHits.get()).isEqualTo(1);
            assertThat(fallbackHits.get()).isZero();
        } finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    private static HttpServer jsonServer(int status, String body, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static LlmProperties propertiesWithFallback(String primaryUrl, String fallbackUrl, boolean enabled) {
        return new LlmProperties(
                new LlmProperties.Endpoint("primary-key", primaryUrl),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "judge", "judge", "plan", "plan", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, 25),
                LlmProperties.TokenLimits.defaults(),
                new LlmProperties.Fallback(enabled,
                        new LlmProperties.Endpoint("fallback-key", fallbackUrl),
                        Map.of("expert", "fallback-model")));
    }
}
