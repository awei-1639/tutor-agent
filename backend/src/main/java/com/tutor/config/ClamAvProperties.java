package com.tutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可选的 ClamAV 守护进程连接；启用扫描后，服务不可用即拒绝请求。 */
@ConfigurationProperties(prefix = "knowledge.scan")
public record ClamAvProperties(boolean enabled, String host, int port, int timeoutSeconds) {
    public String effectiveHost() { return host == null || host.isBlank() ? "localhost" : host; }
    public int effectivePort() { return port <= 0 ? 3310 : port; }
    public int effectiveTimeoutSeconds() { return Math.max(1, timeoutSeconds); }
    public void requireConfigured() {
        if (enabled && (host == null || host.isBlank() || port <= 0)) {
            throw new IllegalStateException("已启用文档恶意文件扫描，但 ClamAV 地址未完成配置");
        }
    }
}
