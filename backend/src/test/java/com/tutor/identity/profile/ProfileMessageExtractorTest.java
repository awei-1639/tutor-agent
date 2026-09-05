package com.tutor.identity.profile;

import com.tutor.contract.Purpose;
import com.tutor.platform.llm.JsonGenerationGateway;
import com.tutor.platform.llm.structured.StructuredOutputService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileMessageExtractorTest {
    @Test
    void ignoresMessagesThatDoNotPassTheProfileGate() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        ProfileMessageExtractor extractor = new ProfileMessageExtractor(
                new StructuredOutputService(gateway, null));

        assertThat(extractor.extract("今天天气不错", "trace").isEmpty()).isTrue();
        verify(gateway, never()).chatJson(any(), anyList(), any(), isNull(), anyInt());
    }

    @Test
    void convertsAndSanitizesAValidStructuredProfileDelta() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        when(gateway.chatJson(eq(Purpose.EXTRACT), anyList(), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"skills":[{"name":"Java","explicit":true}],
                         "scalars":{"target_position":{"value":"后端工程师","explicit":true},
                                    "location":null,"experience_years":null,
                                    "education":null,"daily_hours":null},
                         "preferred_format":["实战项目"]}
                        """);
        ProfileMessageExtractor extractor = new ProfileMessageExtractor(
                new StructuredOutputService(gateway, null));

        ExtractedDelta delta = extractor.extract("我会 Java，目标是后端工程师", "trace");

        assertThat(delta.skills()).extracting(ExtractedDelta.SkillDelta::name)
                .containsExactly("Java");
        assertThat(delta.scalar("target_position").value()).isEqualTo("后端工程师");
        assertThat(delta.preferredFormat()).containsExactly("[NAME_1]");
    }

    @Test
    void treatsInvalidStructuredOutputAsAnEmptyDelta() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        when(gateway.chatJson(eq(Purpose.EXTRACT), anyList(), eq("trace"), isNull(), eq(1)))
                .thenReturn("{}", "{}");
        ProfileMessageExtractor extractor = new ProfileMessageExtractor(
                new StructuredOutputService(gateway, null));

        assertThat(extractor.extract("我会 Java", "trace").isEmpty()).isTrue();
    }
}
