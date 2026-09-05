package com.tutor.platform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void publishesTheTutorApiContractMetadata() {
        var openApi = new OpenApiConfig().tutorOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Personal AI Tutor API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
    }
}
