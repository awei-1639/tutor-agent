package com.tutor.llm;

import com.tutor.contract.Evidence;

import java.util.List;

/** 受控多跳检索的充分性判断能力。 */
public interface RetrievalJudge {
    String judgeSufficient(String query, List<String> evidenceNodeIds, String traceId);

    String judgeSufficientWithEvidence(String query, List<Evidence> evidence, String traceId);

    String judgeSufficientWithEvidence(String originalQuery, String currentSubQuery,
                                       List<Evidence> evidence, String traceId);
}
