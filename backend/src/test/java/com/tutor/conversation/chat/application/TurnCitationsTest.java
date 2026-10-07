package com.tutor.conversation.chat.application;

import com.tutor.contract.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCitationsTest {
    private final TurnCitations citations = new TurnCitations();
    private final List<Evidence> evidences = List.of(
            new Evidence("skill:a", "skill", "skill|A|text", 0.9, null, null, "missing", null),
            new Evidence("skill:b", "skill", "skill|B|text", 0.8, null, null, "missing", null),
            new Evidence("skill:c", "skill", "skill|C|text", 0.7, null, null, "missing", null));

    @Test
    void usedAlignedMapsOnlyMarkerReferencedEvidence() {
        var aligned = citations.usedAligned("引用[S1]与[S3]", evidences, Set.of("S1", "S2", "S3"));
        assertEquals("skill:a", aligned.get(0).nodeId());
        assertNull(aligned.get(1));
        assertEquals("skill:c", aligned.get(2).nodeId());
    }

    @Test
    void usedAlignedIgnoresEvidenceOutsideAvailableCitationIds() {
        var aligned = citations.usedAligned("引用[S2]", evidences, Set.of("S1"));
        // S2 不可引用 → 列表不含任何证据 (未被引用的位置不产生占位)。
        assertTrue(aligned.isEmpty());
    }

    @Test
    void usedAlignedReturnsEmptyForMarkerFreeAnswers() {
        assertEquals(List.of(), citations.usedAligned("问候, 无任何标记", evidences, Set.of("S1")));
    }

    @Test
    void stripSectionArtifactsRemovesParrotedSectionTitles() {
        assertEquals("当前日期是2026年10月7日。",
                TurnCitations.stripSectionArtifacts("当前日期是2026年10月7日[知识证据]。"));
        assertEquals("干净文本", TurnCitations.stripSectionArtifacts("干净文本"));
        assertNull(TurnCitations.stripSectionArtifacts(null));
    }
}
