package com.tutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** application.yml llm.* 的类型化映射 (purpose→model 路由、超时、预算见实现设计 6.1/6.4) */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        Endpoint deepseek,
        Endpoint siliconflow,
        Map<String, String> routing,
        Budget budget,
        Timeout timeout
) {
    public record Endpoint(String apiKey, String baseUrl) {}
    public record Budget(long dailyTokenLimit, long turnTokenLimit) {}
    public record Timeout(int routerSeconds, int chatSeconds, int summarySeconds) {}
}
