package com.tutor.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class TracePersistenceExecutorConfig {
    @Bean(name = "tracePersistenceExecutor", destroyMethod = "")
    public BoundedVirtualThreadExecutor tracePersistenceExecutor(MeterRegistry registry) {
        return new BoundedVirtualThreadExecutor("trace-persistence", 2, 256, registry,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
