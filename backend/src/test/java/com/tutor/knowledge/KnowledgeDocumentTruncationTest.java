package com.tutor.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentTruncationTest {
    @Test
    void retainsDocumentHeadAndTailWhenResourceCapIsReached() {
        String source = "DOCUMENT_HEAD\n" + "middle\n".repeat(100) + "DOCUMENT_TAIL";

        String bounded = KnowledgeDocumentService.retainHeadTail(source, 80);

        assertThat(bounded).hasSizeLessThanOrEqualTo(80);
        assertThat(bounded).contains("DOCUMENT_HEAD", "DOCUMENT_TAIL", "省略");
    }
}
