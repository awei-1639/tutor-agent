package com.tutor.platform.config;

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
        TokenLimits tokens,
        Fallback fallback
) {
    /** 标记唯一的规范构造器，供 Spring Boot 绑定不可变配置记录。 */
    @ConstructorBinding
    public LlmProperties {
    }

    /** 向后兼容的构造器：未显式配置备用供应商时保持单供应商行为。 */
    public LlmProperties(Endpoint deepseek, Endpoint siliconflow, Map<String, String> routing,
                         Budget budget, Timeout timeout, TokenLimits tokens) {
        this(deepseek, siliconflow, routing, budget, timeout, tokens, null);
    }

    public record Endpoint(String apiKey, String baseUrl) {}

    /**
     * 主供应商完全失败后的备用出口。默认关闭，保持单供应商行为不变。
     * 备用供应商同样是 OpenAI 兼容接口 (例如 SiliconFlow 托管的 deepseek 系模型)，
     * 因此仅需换端点与 purpose→model 映射即可承接非流式与首 token 前的流式调用。
     */
    public record Fallback(boolean enabled, Endpoint endpoint, Map<String, String> routing) {
        public boolean isConfigured() {
            return enabled && endpoint != null
                    && endpoint.apiKey() != null && !endpoint.apiKey().isBlank()
                    && endpoint.baseUrl() != null && !endpoint.baseUrl().isBlank();
        }

        public String modelFor(String purpose, String primaryModel) {
            if (routing == null) return primaryModel;
            String mapped = routing.get(purpose);
            return mapped == null || mapped.isBlank() ? primaryModel : mapped;
        }
    }
    public record Budget(long dailyTokenLimit, long turnTokenLimit,
                         long userDailyTokenLimit, int backgroundSharePercent) {
        private static final long DEFAULT_USER_DAILY_TOKEN_LIMIT = 300_000;
        private static final int DEFAULT_BACKGROUND_SHARE_PERCENT = 20;

        /**
         * 必须显式标记规范构造器：本记录还有一个 2 参便捷构造器，多构造器且无标记时
         * Spring Boot 无法选择绑定目标，会把整个 llm.budget 静默绑成 null，
         * 直到第一次 LLM 调用在 LlmBudgetGuard 里 NPE。
         */
        @ConstructorBinding
        public Budget {
            if (userDailyTokenLimit <= 0) userDailyTokenLimit = DEFAULT_USER_DAILY_TOKEN_LIMIT;
            if (backgroundSharePercent <= 0 || backgroundSharePercent >= 100) {
                backgroundSharePercent = DEFAULT_BACKGROUND_SHARE_PERCENT;
            }
        }

        /** 向后兼容构造器：未配置用户/后台配额时取默认值。 */
        public Budget(long dailyTokenLimit, long turnTokenLimit) {
            this(dailyTokenLimit, turnTokenLimit, 0, 0);
        }
    }
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

    public record Timeout(int routerSeconds, int chatSeconds, int summarySeconds, int expertSeconds) {
        /** 使用完整构造器绑定不可变嵌套配置。 */
        @ConstructorBinding
        public Timeout {
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
        if (budget == null || budget.dailyTokenLimit() <= 0 || budget.turnTokenLimit() <= 0
                || budget.userDailyTokenLimit() <= 0 || budget.backgroundSharePercent() <= 0) {
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

    /** 未配置时返回一个关闭的备用配置，避免调用方判空。 */
    public Fallback fallbackOrDisabled() {
        return fallback != null ? fallback : new Fallback(false, null, null);
    }
}
