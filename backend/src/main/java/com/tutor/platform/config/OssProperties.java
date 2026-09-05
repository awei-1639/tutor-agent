package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OSS 仅用于知识库原文件；凭证只存在服务端环境变量中。 */
@ConfigurationProperties(prefix = "oss")
public record OssProperties(
        boolean enabled,
        String endpoint,
        String region,
        String bucket,
        String accessKeyId,
        String accessKeySecret,
        String prefix
) {
    public String normalizedPrefix() {
        if (prefix == null || prefix.isBlank()) return "knowledge-documents/";
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    public void requireConfigured() {
        if (!enabled || blank(endpoint) || blank(region) || blank(bucket)
                || blank(accessKeyId) || blank(accessKeySecret)) {
            throw new IllegalStateException("OSS 未完成配置，无法处理知识库文档");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
