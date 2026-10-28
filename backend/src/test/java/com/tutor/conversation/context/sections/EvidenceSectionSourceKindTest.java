package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TurnContextView;
import com.tutor.platform.text.TokenBudget;
import com.tutor.contract.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSectionSourceKindTest {
    private final TokenBudget budget = new TokenBudget();
    private final EvidenceSection section = new EvidenceSection();

    @Test
    void selfMaterialGetsOwnMarkWhileReferenceStaysPlain() {
        Evidence own = new Evidence("n1", "resource", "我的简历项目经历", 0.9, null,
                "knowledge://document/1#chunk=0", "managed", "hash1", Evidence.KIND_SELF);
        Evidence ref = new Evidence("n2", "skill", "系统设计通用学习路径", 0.8, null, null, null, null);

        ContextSection.Rendered rendered = section.renderWithMetadata(
                new TurnContextView(java.util.Map.of(), List.of(own, ref)), budget);

        assertThat(rendered.text())
                .contains("〔自有〕")
                .contains("[S1]〔自有〕 我的简历项目经历")
                .contains("[S2] 系统设计通用学习路径");
        // 引用编号与标记互不干扰
        assertThat(rendered.citationMarkers().stream().map(ContextSection.CitationMarker::id))
                .containsExactly("S1", "S2");
    }

    @Test
    void headerExplainsTheSelfMarkSoModelCanUseIt() {
        Evidence own = new Evidence("n1", "resource", "内容", 0.9, null, null, null, null,
                Evidence.KIND_SELF);
        ContextSection.Rendered rendered = section.renderWithMetadata(
                new TurnContextView(java.util.Map.of(), List.of(own)), budget);

        assertThat(rendered.text()).contains("用户本人的材料").contains("参考资料");
    }
}
