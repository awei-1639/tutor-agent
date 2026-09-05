package com.tutor.llm.structured;

import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.LlmMessage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredOutputServiceTest {

    @Test
    void acceptsSchemaValidTypedOutput() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        StructuredOutputRecorder recorder = mock(StructuredOutputRecorder.class);
        when(gateway.chatJson(any(), anyList(), anyString(), isNull(), eq(1)))
                .thenReturn("""
                        {"resolved_query":"订单系统支持分库分表吗？","resolved_to":"订单系统",
                         "confidence":0.96,"needs_clarification":false}
                        """);

        StructuredOutputResult<CoreferenceOutput> result = service(gateway, recorder)
                .generate(
                        StructuredTask.COREFERENCE,
                        Purpose.EXTRACT,
                        List.of(LlmMessage.user("question")),
                        CoreferenceOutput.class,
                        output -> assertThat(output.confidence()).isGreaterThan(0.9D),
                        "trace"
                );

        assertThat(result.success()).isTrue();
        assertThat(result.repaired()).isFalse();
        assertThat(result.value().resolvedTo()).isEqualTo("订单系统");
        verify(recorder).record(eq("trace"), eq(StructuredTask.COREFERENCE),
                eq("coreference-v1"), eq(1), anyString(), eq("valid"), anyList());
    }

    @Test
    void repairsSchemaAndBusinessFailureOnce() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        StructuredOutputRecorder recorder = mock(StructuredOutputRecorder.class);
        when(gateway.chatJson(any(), anyList(), anyString(), isNull(), eq(1)))
                .thenReturn(
                        "{\"resolved_query\":\"x\",\"resolved_to\":\"missing\",\"confidence\":2,\"needs_clarification\":false}",
                        """
                        {"resolved_query":"订单系统支持分库分表吗？","resolved_to":"订单系统",
                         "confidence":0.91,"needs_clarification":false}
                        """
                );

        StructuredOutputResult<CoreferenceOutput> result = service(gateway, recorder)
                .generate(
                        StructuredTask.COREFERENCE,
                        Purpose.EXTRACT,
                        List.of(LlmMessage.user("question")),
                        CoreferenceOutput.class,
                        output -> {
                            if (!"订单系统".equals(output.resolvedTo())) {
                                throw new IllegalArgumentException("unknown entity");
                            }
                        },
                        "trace"
                );

        assertThat(result.success()).isTrue();
        assertThat(result.repaired()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
        verify(gateway, times(2)).chatJson(any(), anyList(), anyString(), isNull(), eq(1));
        verify(recorder, times(2)).record(eq("trace"), eq(StructuredTask.COREFERENCE),
                eq("coreference-v1"), anyInt(), anyString(), any(), anyList());
    }

    @Test
    void returnsFailureAfterOneInvalidRepair() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        when(gateway.chatJson(any(), anyList(), anyString(), isNull(), eq(1)))
                .thenReturn("{}", "{\"unknown\":true}");

        StructuredOutputResult<CoreferenceOutput> result = service(gateway, null)
                .generate(
                        StructuredTask.COREFERENCE,
                        Purpose.EXTRACT,
                        List.of(LlmMessage.user("question")),
                        CoreferenceOutput.class,
                        null,
                        "trace"
                );

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.errors()).isNotEmpty();
    }

    private StructuredOutputService service(
            JsonGenerationGateway gateway,
            StructuredOutputRecorder recorder
    ) {
        return new StructuredOutputService(gateway, recorder);
    }
}
