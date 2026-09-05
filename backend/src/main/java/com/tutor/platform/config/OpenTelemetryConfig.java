package com.tutor.platform.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 装配。默认返回 no-op OpenTelemetry (无导出、无开销)；
 * 仅当配置 otel.exporter.otlp.endpoint 时才构建导出到 OTLP 的 TracerProvider。
 * 这样开发和现有部署行为不变，需要接 Langfuse/Jaeger 时只加一个环境变量。
 */
@Configuration
public class OpenTelemetryConfig {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);
    static final String TRACER_NAME = "com.tutor";

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${otel.exporter.otlp.endpoint:}") String otlpEndpoint,
            @Value("${spring.application.name:personal-ai-tutor}") String serviceName) {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            log.info("OpenTelemetry OTLP endpoint 未配置，使用 no-op tracer");
            return OpenTelemetry.noop();
        }
        Resource resource = Resource.getDefault()
                .toBuilder()
                .put("service.name", serviceName)
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder().setEndpoint(otlpEndpoint).build()).build())
                .setResource(resource)
                .build();
        log.info("OpenTelemetry 追踪已启用 endpoint={}", otlpEndpoint);
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    public Tracer tutorTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(TRACER_NAME);
    }
}
