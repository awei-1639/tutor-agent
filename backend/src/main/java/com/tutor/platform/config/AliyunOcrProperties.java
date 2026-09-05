package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 阿里云通用 OCR，仅用于文本量较少的 PDF 页面。 */
@ConfigurationProperties(prefix = "knowledge.ocr.aliyun")
public record AliyunOcrProperties(boolean enabled, String region, String accessKeyId, String accessKeySecret,
                                  int textDensityThreshold, int maxPages, int timeoutSeconds) {
    public String effectiveRegion() { return region == null || region.isBlank() ? "cn-hangzhou" : region; }
    public int effectiveDensityThreshold() { return Math.max(20, textDensityThreshold); }
    public int effectiveMaxPages() { return Math.max(1, maxPages); }
    public int effectiveTimeoutSeconds() { return Math.max(1, timeoutSeconds); }
    public void requireConfigured() {
        if (enabled && (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank())) {
            throw new IllegalStateException("已启用阿里云 OCR，但 AccessKey 未完成配置");
        }
    }
}
