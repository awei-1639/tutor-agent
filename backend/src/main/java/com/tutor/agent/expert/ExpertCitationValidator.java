package com.tutor.agent.expert;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 校验专家输出引用只能指向本次简报中实际渲染的证据。 */
final class ExpertCitationValidator {
    private static final Pattern CITATION = Pattern.compile("S[1-9][0-9]*");

    List<String> validate(JsonNode node, Set<String> availableIds, int maxItems) {
        if (node == null) return List.of();
        if (!node.isArray() || node.size() > maxItems) throw new IllegalStateException("专家 citations 格式不合法");
        List<String> citations = new ArrayList<>();
        node.forEach(value -> {
            String citation = value.asText("");
            if (!CITATION.matcher(citation).matches()) throw new IllegalStateException("专家 citations 含非法引用");
            if (!availableIds.contains(citation)) {
                throw new IllegalStateException("专家 citations 引用了本次简报不存在的证据: " + citation);
            }
            citations.add(citation);
        });
        return List.copyOf(citations);
    }
}
