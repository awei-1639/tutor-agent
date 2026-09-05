package com.tutor.platform.llm;

import com.tutor.contract.Evidence;

import java.util.List;

/** 构建多跳检索证据充分性判断所需的有界 Prompt。 */
final class RetrievalJudgePromptFactory {
    List<LlmMessage> forNodeIds(String query, List<String> nodeIds) {
        String prompt = "查询: " + query + "\n已累积证据节点ID: " + nodeIds + "\n证据正文未提供时只能作保守判断。"
                + "\n请判断这些证据是否足以完整回答查询。输出JSON {sufficient: bool, followup_query: string|null, missing: string|null}";
        return List.of(LlmMessage.system("你是多跳检索的证据充分性判断器。判断规则: "
                        + "1) 若证据覆盖了回答问题所需的所有关键概念/前置技能/资源→sufficient=true; "
                        + "2) 否则→sufficient=false, followup_query=针对缺口的更窄查询, missing=缺失的关键概念关键词; "
                        + "3) followup_query 不应与原 query 重复, 应聚焦缺失的具体子概念。"), LlmMessage.user(prompt));
    }

    List<LlmMessage> forEvidence(String query, List<Evidence> evidence) {
        return forEvidence(query, query, evidence);
    }

    List<LlmMessage> forEvidence(String originalQuery, String currentSubQuery, List<Evidence> evidence) {
        StringBuilder prompt = new StringBuilder("原始问题: ").append(clip(originalQuery, 600))
                .append("\n当前子问题: ").append(clip(currentSubQuery, 600))
                .append("\n已累积证据（最多 8 条）:\n");
        if (evidence == null || evidence.isEmpty()) prompt.append("（无）");
        else evidence.stream().limit(8).forEach(item -> prompt.append("- id=").append(clip(item.nodeId(), 120))
                .append(" type=").append(clip(item.nodeType(), 40)).append(" text=").append(clip(item.chunkText(), 500))
                .append(" path=").append(clip(item.graphPath(), 300)).append(" source=").append(clip(item.sourceStatus(), 40)).append('\n'));
        prompt.append("请同时判断原始问题和当前子问题：证据是否已经覆盖原始问题所需的关键概念和关系。若不充分，followup_query 应针对当前子问题的明确缺口。输出JSON {sufficient: bool, followup_query: string|null, missing: string|null}");
        return List.of(LlmMessage.system("你是多跳检索的证据充分性判断器。必须依据证据正文和图路径判断，不能仅因节点名称相似就判定充分。"
                        + "若不充分，followup_query 只能针对当前子问题的明确缺口，长度不超过 240 个字符，不得重复原查询或改变主题；"
                        + "若充分则 followup_query=null。"), LlmMessage.user(prompt.toString()));
    }

    private static String clip(String value, int maxChars) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("[\\u0000-\\u001f\\u007f]", " ").replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }
}
