package com.tutor.agent.guard;

import com.tutor.contract.Evidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSourcePolicyTest {
    @Test
    void recognizesManagedKnowledgeOnlyWhenStoredHashMatches() {
        String text = "受管理的知识文本";
        Evidence evidence = new Evidence("doc:1:0", "document", text, 0.9, null,
                "knowledge://document/1#chunk=0", "managed", CitationSourcePolicy.sha256(text));

        var provenance = CitationSourcePolicy.inspect(evidence);

        assertThat(provenance.sourceStatus()).isEqualTo("managed");
        assertThat(provenance.sourceUrl()).startsWith("knowledge://document/1");
    }

    @Test
    void reportsIntegrityMismatchInsteadOfTrustingIngestionLabel() {
        Evidence evidence = new Evidence("doc:1:0", "document", "已被篡改", 0.9, null,
                "knowledge://document/1#chunk=0", "managed", CitationSourcePolicy.sha256("原始文本"));

        assertThat(CitationSourcePolicy.inspect(evidence).sourceStatus()).isEqualTo("integrity_mismatch");
    }

    @Test
    void neverElevatesUnverifiedHttpsOrUnsafeSchemes() {
        Evidence https = new Evidence("res:1", "resource", "text", 0.8, null,
                "https://example.com/a", "verified", null);
        Evidence unsafe = new Evidence("res:2", "resource", "text", 0.8, null,
                "http://127.0.0.1/admin", "verified", null);

        assertThat(CitationSourcePolicy.inspect(https).sourceStatus()).isEqualTo("unverified");
        assertThat(CitationSourcePolicy.inspect(unsafe).sourceStatus()).isEqualTo("invalid");
        assertThat(CitationSourcePolicy.inspect(unsafe).sourceUrl()).isBlank();
    }
}
