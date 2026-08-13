package com.tutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** application.yml llm.* 的类型化映射 (purpose→model 路由、超时、预算见实现设计 6.1/6.4) */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        Endpoint deepseek,
        Endpoint siliconflow,
        Map<String, String> routing,
        Budget budget,
        Timeout timeout,
        TokenLimits tokens
) {
    /**
     * There is also a five-argument compatibility constructor below.  Mark the
     * canonical constructor explicitly so Spring Boot does not fall back to
     * JavaBean binding (which would require a no-arg constructor for this
     * immutable record).
     */
    @ConstructorBinding
    public LlmProperties {
    }

    public record Endpoint(String apiKey, String baseUrl) {}
    public record Budget(long dailyTokenLimit, long turnTokenLimit) {}
    public record PurposeLimit(int inputTokens, int outputTokens) {
        public PurposeLimit {
            if (inputTokens <= 0 || outputTokens <= 0) {
                throw new IllegalArgumentException("LLM token limits must be positive");
            }
        }
    }

    public record TokenLimits(
            PurposeLimit router,
            PurposeLimit chat,
            PurposeLimit expert,
            PurposeLimit summary,
            PurposeLimit extract,
            PurposeLimit judge,
            PurposeLimit plan,
            int embedInputTokens,
            int rerankInputTokens
    ) {
        public TokenLimits {
            if (router == null || chat == null || expert == null || summary == null
                    || extract == null || judge == null || plan == null
                    || embedInputTokens <= 0 || rerankInputTokens <= 0) {
                throw new IllegalArgumentException("LLM token limits must be configured");
            }
        }

        public static TokenLimits defaults() {
            return new TokenLimits(
                    new PurposeLimit(1_200, 96),
                    new PurposeLimit(8_000, 1_600),
                    new PurposeLimit(5_000, 1_800),
                    new PurposeLimit(5_000, 600),
                    new PurposeLimit(8_000, 1_600),
                    new PurposeLimit(3_500, 600),
                    new PurposeLimit(6_000, 1_800),
                    8_000,
                    6_000);
        }
    }

    /** Backward-compatible constructor for focused tests and local callers. */
    public LlmProperties(Endpoint deepseek, Endpoint siliconflow, Map<String, String> routing,
                         Budget budget, Timeout timeout) {
        this(deepseek, siliconflow, routing, budget, timeout, TokenLimits.defaults());
    }

    public record Timeout(int routerSeconds, int chatSeconds, int summarySeconds, int expertSeconds) {
        /** Select the full constructor when binding the nested immutable record. */
        @ConstructorBinding
        public Timeout {
        }

        /** Backward-compatible constructor for focused tests and local callers. */
        public Timeout(int routerSeconds, int chatSeconds, int summarySeconds) {
            this(routerSeconds, chatSeconds, summarySeconds, 25);
        }
    }

    /** 生产环境在接收流量前校验，避免空密钥或无效路由在首个用户请求时才失败。 */
    public void requireProductionConfiguration() {
        requireEndpoint("llm.deepseek", deepseek);
        requireEndpoint("llm.siliconflow", siliconflow);
        if (routing == null) throw new IllegalStateException("llm.routing must be configured");
        for (String purpose : new String[] {"chat", "router", "expert", "summary", "extract", "judge", "plan", "embed"}) {
            if (blank(routing.get(purpose))) {
                throw new IllegalStateException("llm.routing." + purpose + " must be configured");
            }
        }
        if (budget == null || budget.dailyTokenLimit() <= 0 || budget.turnTokenLimit() <= 0) {
            throw new IllegalStateException("llm budget limits must be positive");
        }
        if (tokens == null) {
            throw new IllegalStateException("llm.tokens must be configured");
        }
        if (timeout == null || timeout.routerSeconds() <= 0 || timeout.chatSeconds() <= 0
                || timeout.summarySeconds() <= 0 || timeout.expertSeconds() <= 0) {
            throw new IllegalStateException("llm timeout values must be positive");
        }
    }

    private static void requireEndpoint(String name, Endpoint endpoint) {
        if (endpoint == null || blank(endpoint.apiKey()) || blank(endpoint.baseUrl())) {
            throw new IllegalStateException(name + " api-key and base-url must be configured");
        }
        try {
            URI uri = URI.create(endpoint.baseUrl());
            if (!Objects.equals(uri.getScheme(), "https") && !Objects.equals(uri.getScheme(), "http")) {
                throw new IllegalArgumentException("unsupported scheme");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " base-url must be an HTTP(S) URL", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
