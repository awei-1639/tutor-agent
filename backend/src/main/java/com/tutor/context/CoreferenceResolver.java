package com.tutor.context;

import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.CoreferenceOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.memory.local.ConversationStore;
import com.tutor.llm.LlmMessage;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对短对话追问做确定性的指代解析。
 *
 * 这里只从最近会话中已经出现的候选实体里选择，不调用 LLM，也不凭空创建实体。
 * 无法唯一确定时返回 needsClarification=true，由 ChatService 负责追问。
 */
public final class CoreferenceResolver {
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_CHARS = 6000;
    private static final double MIN_LLM_CONFIDENCE = 0.85D;
    private static final Pattern REFERENCE = Pattern.compile(
            "前者|后者|这个岗位|该岗位|这个项目|该项目|这个系统|该系统|"
                    + "这家公司|这个公司|它们|他们|她们|他|她|它|这个|那个");
    private static final Pattern ENTITY = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9+#.·_-]{2,40}(?:岗位|职位|项目|系统|平台|公司|简历|模型|数据库))");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile(
            "[。！？；，,、\\s]+|的|和|与|及|是|为|比较|讨论|包括|使用|采用|用了|值得|保留|都");
    private final StructuredOutputService structuredOutputService;

    public CoreferenceResolver() {
        this(new StructuredOutputService(null, null));
    }

    public CoreferenceResolver(JsonGenerationGateway jsonGateway) {
        this(new StructuredOutputService(jsonGateway, null));
    }

    public CoreferenceResolver(StructuredOutputService structuredOutputService) {
        this.structuredOutputService = structuredOutputService;
    }

    public CoreferenceResult resolve(
            String question,
            List<ConversationStore.Msg> history
    ) {
        return resolve(question, history, null);
    }

    public CoreferenceResult resolve(
            String question,
            List<ConversationStore.Msg> history,
            String traceId
    ) {
        if (question == null || question.isBlank()) {
            return CoreferenceResult.unchanged(question);
        }

        Matcher referenceMatcher = REFERENCE.matcher(question);
        if (!referenceMatcher.find()) {
            return CoreferenceResult.unchanged(question);
        }

        List<EntityCandidate> candidates = extractCandidates(history);
        String mention = referenceMatcher.group();
        EntityCandidate target = chooseTarget(mention, candidates);
        if (target == null) {
            CoreferenceResult llmResult = resolveWithLlm(
                    question, mention, history, candidates, traceId);
            if (llmResult != null) return llmResult;
            return CoreferenceResult.clarification(question, mention);
        }

        String resolved = replaceOne(question, referenceMatcher, target.value());
        return new CoreferenceResult(
                question,
                resolved,
                List.of(new CoreferenceResult.Reference(
                        mention,
                        target.value(),
                        target.confidence()
                )),
                false
        );
    }

    /**
     * 规则无法唯一确定时才调用 LLM。模型输出必须引用历史中原样出现的实体，
     * 否则直接降级为澄清，避免模型凭空制造指代目标。
     */
    private CoreferenceResult resolveWithLlm(
            String question,
            String mention,
            List<ConversationStore.Msg> history,
            List<EntityCandidate> candidates,
            String traceId
    ) {
        String historyText = historyText(history);
        String candidateText = candidates.stream()
                .map(EntityCandidate::value)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("无");
        String prompt = """
                请解析当前问题中的指代表达，只做指代消解，不回答问题。

                约束：
                1. resolved_to 必须是历史上下文中原样出现的实体；
                2. 不要创建历史中没有的人、公司、岗位或项目；
                3. 无法唯一判断时 needs_clarification=true；
                4. resolved_query 只补全指代，不改变用户意图；
                5. 只返回严格 JSON，不要 Markdown。

                已识别候选实体：%s
                历史上下文：
                %s

                当前问题：%s
                当前指代表达：%s

                JSON 格式：
                {"resolved_query":"...","resolved_to":"...",
                 "confidence":0.0,"needs_clarification":false}
                """.formatted(candidateText, historyText, question, mention);

        StructuredOutputResult<CoreferenceOutput> result =
                structuredOutputService.generate(
                        StructuredTask.COREFERENCE,
                        com.tutor.contract.Purpose.EXTRACT,
                        List.of(
                                LlmMessage.system("你是严格的中文对话指代解析器。"),
                                LlmMessage.user(prompt)
                        ),
                        CoreferenceOutput.class,
                        output -> validateBusinessOutput(
                                output, question, mention, historyText),
                        traceId == null || traceId.isBlank() ? "coreference" : traceId
                );
        if (!result.success()) return null;

        CoreferenceOutput output = result.value();
        if (output.needsClarification()) return null;
        return new CoreferenceResult(
                question,
                output.resolvedQuery(),
                List.of(new CoreferenceResult.Reference(
                        mention, output.resolvedTo(), output.confidence())),
                false
        );
    }

    private void validateBusinessOutput(
            CoreferenceOutput output,
            String question,
            String mention,
            String historyText
    ) {
        if (output.needsClarification()) return;
        String resolvedTo = output.resolvedTo() == null ? "" : output.resolvedTo().trim();
        String resolvedQuery = output.resolvedQuery() == null ? "" : output.resolvedQuery().trim();
        if (resolvedTo.isBlank() || resolvedQuery.isBlank()) {
            throw new IllegalArgumentException("resolved entity and query are required");
        }
        if (output.confidence() < MIN_LLM_CONFIDENCE) {
            throw new IllegalArgumentException("confidence below threshold");
        }
        if (!historyText.contains(resolvedTo)) {
            throw new IllegalArgumentException("resolved entity is not present in history");
        }
        if (!resolvedQuery.contains(resolvedTo)
                || !sameIntentShape(question, mention, resolvedQuery, resolvedTo)) {
            throw new IllegalArgumentException("resolved query changed intent shape");
        }
    }

    private boolean sameIntentShape(
            String question,
            String mention,
            String resolvedQuery,
            String resolvedTo
    ) {
        String originalRemainder = question.replaceFirst(
                Pattern.quote(mention), "");
        String resolvedRemainder = resolvedQuery.replaceFirst(
                Pattern.quote(resolvedTo), "");
        return originalRemainder.trim().equals(resolvedRemainder.trim());
    }

    private String historyText(List<ConversationStore.Msg> history) {
        if (history == null || history.isEmpty()) return "无";
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        StringBuilder result = new StringBuilder();
        for (int index = start; index < history.size(); index++) {
            ConversationStore.Msg message = history.get(index);
            if (message == null || message.content == null || message.content.isBlank()) continue;
            result.append("[角色=").append(message.role).append("] ")
                    .append(message.content).append('\n');
            if (result.length() >= MAX_HISTORY_CHARS) break;
        }
        if (result.length() > MAX_HISTORY_CHARS) {
            return result.substring(result.length() - MAX_HISTORY_CHARS);
        }
        return result.toString();
    }

    private List<EntityCandidate> extractCandidates(List<ConversationStore.Msg> history) {
        if (history == null || history.isEmpty()) return List.of();

        Map<String, EntityCandidate> unique = new LinkedHashMap<>();
        for (int index = 0; index < history.size(); index++) {
            ConversationStore.Msg message = history.get(index);
            if (message == null || message.content == null) continue;

            String[] segments = SEGMENT_SEPARATOR.split(message.content);
            for (String segment : segments) {
                Matcher matcher = ENTITY.matcher(segment.trim());
                if (!matcher.find()) continue;
                String value = matcher.group(1).trim();
                if (value.isBlank()) continue;
                unique.put(value, new EntityCandidate(
                        value,
                        EntityCandidate.EntityType.from(value),
                        index,
                        0D
                ));
            }
        }

        return unique.values().stream()
                .sorted(Comparator.comparingInt(EntityCandidate::turnIndex))
                .toList();
    }

    private EntityCandidate chooseTarget(
            String mention,
            List<EntityCandidate> candidates
    ) {
        if (candidates.isEmpty()) return null;

        if ("前者".equals(mention) || "后者".equals(mention)) {
            List<EntityCandidate> distinct = distinctCandidates(candidates);
            if (distinct.size() < 2) return null;
            int targetIndex = "前者".equals(mention) ? 0 : 1;
            EntityCandidate target = distinct.get(targetIndex);
            return target.withConfidence(0.98D);
        }

        EntityCandidate.EntityType expected = EntityCandidate.EntityType.fromMention(mention);
        List<EntityCandidate> compatible = candidates.stream()
                .filter(candidate -> expected == EntityCandidate.EntityType.UNKNOWN
                        || candidate.type() == expected)
                .toList();
        if (compatible.isEmpty()) return null;

        EntityCandidate latest = compatible.getLast();
        if (compatible.size() == 1) return latest.withConfidence(0.96D);

        // 只有最近实体比其他候选新很多时才自动解析；否则让用户澄清。
        EntityCandidate previous = compatible.get(compatible.size() - 2);
        if (latest.turnIndex() - previous.turnIndex() >= 2) {
            return latest.withConfidence(0.86D);
        }
        return null;
    }

    private List<EntityCandidate> distinctCandidates(List<EntityCandidate> candidates) {
        Map<String, EntityCandidate> distinct = new LinkedHashMap<>();
        for (EntityCandidate candidate : candidates) {
            distinct.putIfAbsent(candidate.value(), candidate);
        }
        return new ArrayList<>(distinct.values());
    }

    private String replaceOne(String question, Matcher matcher, String replacement) {
        StringBuffer result = new StringBuffer();
        matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        matcher.appendTail(result);
        return result.toString();
    }

    public record EntityCandidate(
            String value,
            EntityType type,
            int turnIndex,
            double confidence
    ) {
        EntityCandidate withConfidence(double value) {
            return new EntityCandidate(this.value, this.type, this.turnIndex, value);
        }

        enum EntityType {
            PERSON,
            JOB,
            PROJECT,
            SYSTEM,
            COMPANY,
            DOCUMENT,
            TECHNOLOGY,
            UNKNOWN;

            static EntityType from(String value) {
                if (value.contains("岗位") || value.contains("职位")) return JOB;
                if (value.contains("项目")) return PROJECT;
                if (value.contains("系统") || value.contains("平台")) return SYSTEM;
                if (value.contains("公司")) return COMPANY;
                if (value.contains("简历")) return DOCUMENT;
                if (value.contains("模型") || value.contains("数据库")) return TECHNOLOGY;
                return UNKNOWN;
            }

            static EntityType fromMention(String mention) {
                if (mention.equals("他") || mention.equals("她")
                        || mention.equals("他们") || mention.equals("她们")) {
                    return PERSON;
                }
                if (mention.contains("岗位")) return JOB;
                if (mention.contains("项目")) return PROJECT;
                if (mention.contains("系统")) return SYSTEM;
                if (mention.contains("公司")) return COMPANY;
                return UNKNOWN;
            }
        }
    }
}
