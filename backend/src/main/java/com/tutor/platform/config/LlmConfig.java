package com.tutor.platform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用各平台/知识域的配置属性绑定。Neo4j 驱动装配见 {@link Neo4jConfig}。 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, Mem0Properties.class, OssProperties.class,
        ClamAvProperties.class, AliyunOcrProperties.class, KnowledgeUploadProperties.class,
        KnowledgeIngestionProperties.class})
public class LlmConfig {
}
