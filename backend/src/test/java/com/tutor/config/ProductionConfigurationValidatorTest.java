package com.tutor.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationValidatorTest {
    @Test
    void acceptsCompleteProductionConfiguration() {
        assertDoesNotThrow(() -> validator(validLlm(), new OssProperties(false, "", "", "", "", "", ""),
                new Mem0Properties(false, "", "", 2)).afterSingletonsInstantiated());
    }

    @Test
    void rejectsMissingLlmCredential() {
        LlmProperties llm = new LlmProperties(
                new LlmProperties.Endpoint("", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                routing(), new LlmProperties.Budget(1, 1), new LlmProperties.Timeout(1, 1, 1));

        assertThrows(IllegalStateException.class, () -> validator(llm,
                new OssProperties(false, "", "", "", "", "", ""),
                new Mem0Properties(false, "", "", 2)).afterSingletonsInstantiated());
    }

    @Test
    void rejectsEnabledMem0WithoutApiKey() {
        assertThrows(IllegalStateException.class, () -> validator(validLlm(),
                new OssProperties(false, "", "", "", "", "", ""),
                new Mem0Properties(true, "https://api.mem0.ai", "", 2)).afterSingletonsInstantiated());
    }

    private static ProductionConfigurationValidator validator(LlmProperties llm, OssProperties oss, Mem0Properties mem0) {
        return new ProductionConfigurationValidator(llm, oss, mem0);
    }

    private static LlmProperties validLlm() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                routing(), new LlmProperties.Budget(100, 10), new LlmProperties.Timeout(1, 1, 1));
    }

    private static Map<String, String> routing() {
        return Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                "extract", "extract", "judge", "judge", "plan", "plan", "embed", "embed");
    }
}
