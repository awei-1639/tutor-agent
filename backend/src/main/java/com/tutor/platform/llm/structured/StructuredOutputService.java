package com.tutor.platform.llm.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.platform.llm.JsonGenerationGateway;
import com.tutor.platform.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Schema 校验、强类型反序列化、一次修复和审计记录的统一入口。 */
@Component
public class StructuredOutputService {
    private static final Logger log = LoggerFactory.getLogger(StructuredOutputService.class);
    private static final int MAX_REPAIR_RAW_CHARS = 8000;
    private final JsonGenerationGateway gateway;
    private final StructuredOutputRecorder recorder;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredOutputService(
            JsonGenerationGateway gateway,
            StructuredOutputRecorder recorder
    ) {
        this.gateway = gateway;
        this.recorder = recorder;
    }

    public <T> StructuredOutputResult<T> generate(
            StructuredTask task,
            Purpose purpose,
            List<LlmMessage> messages,
            Class<T> outputType,
            Consumer<T> businessValidator,
            String traceId
    ) {
        return generate(task, purpose, messages, outputType, businessValidator, null, traceId);
    }

    public <T> StructuredOutputResult<T> generate(
            StructuredTask task,
            Purpose purpose,
            List<LlmMessage> messages,
            Class<T> outputType,
            Consumer<T> businessValidator,
            Duration requestTimeout,
            String traceId
    ) {
        StructuredSchemaRegistry.Definition<T> definition = definition(task, outputType);
        if (gateway == null) {
            return StructuredOutputResult.failure(null, 0, List.of(
                    new StructuredOutputError("provider", "", "structured output gateway unavailable")));
        }
        String raw;
        try {
            raw = gateway.chatJson(
                    purpose,
                    contractMessages(messages, definition),
                    traceId,
                    requestTimeout,
                    1
            );
        } catch (RuntimeException error) {
            log.warn("structured output provider failure task={} trace={} type={}",
                    task, traceId, error.getClass().getSimpleName());
            return StructuredOutputResult.failure(null, 1, List.of(
                    new StructuredOutputError("provider", "", error.getClass().getSimpleName())));
        }

        Validation<T> first = validate(definition, raw, businessValidator);
        record(traceId, task, definition.schemaId(), 1, raw, first);
        if (first.success()) {
            return new StructuredOutputResult<>(true, first.value(), raw, false, 1, List.of());
        }

        String repairedRaw;
        try {
            repairedRaw = gateway.chatJson(
                    purpose,
                    repairMessages(messages, definition, raw, first.errors()),
                    traceId,
                    requestTimeout,
                    1
            );
        } catch (RuntimeException error) {
            record(traceId, task, definition.schemaId(), 2, null,
                    Validation.failure(List.of(new StructuredOutputError(
                            "repair_provider", "", error.getClass().getSimpleName()))));
            return StructuredOutputResult.failure(raw, 1, first.errors());
        }

        Validation<T> repaired = validate(definition, repairedRaw, businessValidator);
        record(traceId, task, definition.schemaId(), 2, repairedRaw, repaired);
        if (repaired.success()) {
            return new StructuredOutputResult<>(true, repaired.value(), repairedRaw, true, 2, List.of());
        }
        return StructuredOutputResult.failure(repairedRaw, 2, repaired.errors());
    }

    @SuppressWarnings("unchecked")
    private <T> StructuredSchemaRegistry.Definition<T> definition(
            StructuredTask task,
            Class<T> outputType
    ) {
        StructuredSchemaRegistry.Definition<?> definition =
                StructuredSchemaRegistry.get(task);
        if (!definition.outputType().equals(outputType)) {
            throw new IllegalArgumentException("structured output type mismatch for " + task);
        }
        return (StructuredSchemaRegistry.Definition<T>) definition;
    }

    private <T> Validation<T> validate(
            StructuredSchemaRegistry.Definition<T> definition,
            String raw,
            Consumer<T> businessValidator
    ) {
        if (raw == null || raw.isBlank()) {
            return Validation.failure(List.of(
                    new StructuredOutputError("parse", "", "empty output")));
        }
        try {
            JsonNode node = mapper.readTree(raw);
            List<StructuredOutputError> errors = definition.schema().validate(node).stream()
                    .map(error -> new StructuredOutputError(
                            "schema",
                            error.getInstanceLocation().toString(),
                            error.getMessage()))
                    .toList();
            if (!errors.isEmpty()) return Validation.failure(errors);

            T value = mapper.treeToValue(node, definition.outputType());
            if (businessValidator != null) businessValidator.accept(value);
            return Validation.success(value);
        } catch (Exception error) {
            return Validation.failure(List.of(new StructuredOutputError(
                    "business", "", error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage())));
        }
    }

    private List<LlmMessage> repairMessages(
            List<LlmMessage> original,
            StructuredSchemaRegistry.Definition<?> definition,
            String raw,
            List<StructuredOutputError> errors
    ) {
        String boundedRaw = raw.length() <= MAX_REPAIR_RAW_CHARS
                ? raw : raw.substring(0, MAX_REPAIR_RAW_CHARS);
        String errorText = errors.stream()
                .map(error -> error.path() + ": " + error.message())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("unknown validation error");
        String repairPrompt = """
                修复上一轮结构化输出。只返回一个 JSON object，不要 Markdown、解释或代码块。
                Schema ID：%s
                Schema：%s
                校验错误：
                %s
                原始输出：
                %s
                """.formatted(definition.schemaId(), definition.schemaJson(), errorText, boundedRaw);
        List<LlmMessage> repaired = new ArrayList<>(original == null ? List.of() : original);
        repaired.add(LlmMessage.system(repairPrompt));
        repaired.add(LlmMessage.user("请只返回修复后的 JSON。"));
        return List.copyOf(repaired);
    }

    private List<LlmMessage> contractMessages(
            List<LlmMessage> original,
            StructuredSchemaRegistry.Definition<?> definition
    ) {
        List<LlmMessage> result = new ArrayList<>();
        result.add(LlmMessage.system("""
                你必须输出符合以下结构化契约的 JSON object。
                Schema ID：%s
                Schema：%s
                不要输出 Markdown、解释、代码块或额外字段。
                """.formatted(definition.schemaId(), definition.schemaJson())));
        if (original != null) result.addAll(original);
        return List.copyOf(result);
    }

    private <T> void record(
            String traceId,
            StructuredTask task,
            String schemaId,
            int attempt,
            String raw,
            Validation<T> validation
    ) {
        if (recorder != null) {
            recorder.record(traceId, task, schemaId, attempt, raw,
                    validation.success() ? "valid" : "invalid", validation.errors());
        }
    }

    private record Validation<T>(boolean success, T value, List<StructuredOutputError> errors) {
        static <T> Validation<T> success(T value) {
            return new Validation<>(true, value, List.of());
        }

        static <T> Validation<T> failure(List<StructuredOutputError> errors) {
            return new Validation<>(false, null, errors == null ? List.of() : List.copyOf(errors));
        }
    }
}
