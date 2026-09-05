package com.tutor.platform.config;

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
                routing(), new LlmProperties.Budget(1, 1), new LlmProperties.Timeout(1, 1, 1, 25),
                LlmProperties.TokenLimits.defaults());

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

    @Test
    void rejectsEnabledOcrWithoutCredentials() {
        assertThrows(IllegalStateException.class, () -> new ProductionConfigurationValidator(validLlm(),
                new OssProperties(false, "", "", "", "", "", ""),
                new Mem0Properties(false, "", "", 2), new ClamAvProperties(false, "", 0, 2),
                new AliyunOcrProperties(true, "cn-hangzhou", "", "", 80, 100, 15)).afterSingletonsInstantiated());
    }

    private static ProductionConfigurationValidator validator(LlmProperties llm, OssProperties oss, Mem0Properties mem0) {
        return new ProductionConfigurationValidator(llm, oss, mem0, new ClamAvProperties(false, "", 0, 2),
                new AliyunOcrProperties(false, "", "", "", 80, 100, 15));
    }

    private static LlmProperties validLlm() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                routing(), new LlmProperties.Budget(100, 10), new LlmProperties.Timeout(1, 1, 1, 25),
                LlmProperties.TokenLimits.defaults());
    }

    private static Map<String, String> routing() {
        return Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                "extract", "extract", "judge", "judge", "plan", "plan", "embed", "embed");
    }
}
