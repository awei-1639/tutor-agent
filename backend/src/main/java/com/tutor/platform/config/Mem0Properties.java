package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mem0 Platform REST 配置；默认关闭，避免本地开发无意外外呼。 */
@ConfigurationProperties(prefix = "memory.mem0")
public record Mem0Properties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        int timeoutSeconds
) {
    public boolean configured() {
        return enabled && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    public void requireEnabledConfiguration() {
        if (enabled && !configured()) {
            throw new IllegalStateException("memory.mem0 is enabled but base-url or api-key is missing");
        }
    }
}
