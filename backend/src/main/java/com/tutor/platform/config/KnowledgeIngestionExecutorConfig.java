package com.tutor.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当前 JVM 内文档 Embedding 请求的统一准入入口。 */
@Configuration
public class KnowledgeIngestionExecutorConfig {
    @Bean(name = "knowledgeEmbeddingExecutor", destroyMethod = "")
    public BoundedVirtualThreadExecutor knowledgeEmbeddingExecutor(KnowledgeIngestionProperties properties,
                                                                    MeterRegistry registry) {
        int queueCapacity = Math.max(16, properties.embeddingConcurrency() * properties.maxInFlight() * 2);
        return new BoundedVirtualThreadExecutor("knowledge-embedding", properties.embeddingConcurrency(),
                queueCapacity, registry);
    }
}
