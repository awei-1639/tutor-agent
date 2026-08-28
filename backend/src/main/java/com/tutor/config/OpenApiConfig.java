package com.tutor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI tutorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Personal AI Tutor API")
                .version("v1")
                .description("API contract for the Personal AI Tutor web client and integrations."));
    }
}
