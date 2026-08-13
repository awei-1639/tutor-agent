package com.tutor.context;

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
}
