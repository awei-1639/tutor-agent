package com.tutor.conversation.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {
    @Test
    void truncateNeverExceedsRequestedTokenLimit() {
        TokenBudget budget = new TokenBudget();
        String source = "这是一段很长的中文文本，用于验证截断后的省略号也不会突破硬 token 上限。";

        for (int limit = 1; limit <= 24; limit++) {
            assertThat(budget.count(budget.truncate(source, limit))).isLessThanOrEqualTo(limit);
        }
    }

    @Test
    void headTailPreservesBothEndsWithinTokenLimit() {
        TokenBudget budget = new TokenBudget();
        String source = "开头背景和目标。" + "中间内容。".repeat(2_000) + "结尾限制条件和最终问题。";

        String bounded = budget.headTail(source, 120, 0.6D);

        assertThat(budget.count(bounded)).isLessThanOrEqualTo(120);
        assertThat(bounded).startsWith("开头背景和目标").contains("结尾限制条件和最终问题");
    }

    @Test
    void prefixAndSuffixDoNotAddMarkers() {
        TokenBudget budget = new TokenBudget();
        String source = "头部" + "中间".repeat(500) + "尾部";

        assertThat(budget.prefix(source, 10)).doesNotEndWith("…");
        assertThat(budget.suffix(source, 10)).doesNotStartWith("…");
    }
}
