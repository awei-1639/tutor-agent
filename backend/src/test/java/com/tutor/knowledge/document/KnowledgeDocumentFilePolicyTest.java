package com.tutor.knowledge.document;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentFilePolicyTest {

    @Test
    void sanitizesKnownResourceKindsAndDefaultsBlankToNull() {
        assertThat(KnowledgeDocumentFilePolicy.sanitizeResourceKind(null)).isNull();
        assertThat(KnowledgeDocumentFilePolicy.sanitizeResourceKind("  ")).isNull();
        assertThat(KnowledgeDocumentFilePolicy.sanitizeResourceKind("document")).isEqualTo("document");
        assertThat(KnowledgeDocumentFilePolicy.sanitizeResourceKind(" RESOURCE ")).isEqualTo("resource");
        assertThat(KnowledgeDocumentFilePolicy.sanitizeResourceKind("Job")).isEqualTo("job");
    }

    @Test
    void rejectsUnknownResourceKindsInsteadOfGuessing() {
        assertThatThrownBy(() -> KnowledgeDocumentFilePolicy.sanitizeResourceKind("course"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("resourceKind");
    }
}
