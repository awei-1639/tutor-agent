package com.tutor.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 唯一的 system prompt 组装入口 (实现设计 3.3)。
 * 分区列表由 Spring 按 @Order 注入 (区1规则→区2画像→区4证据; 区3情景/区5摘要随 L2 落地接入)。
 * 会话历史不在此组装, 以 message 列表形式单独传递 (区5+6)。
 */
@Component
public class PromptAssembler {
    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);
    private final List<ContextSection> sections;
    private final TokenBudget budget;

    public PromptAssembler(List<ContextSection> sections, TokenBudget budget) {
        this.sections = sections;
        this.budget = budget;
    }

    public String assemble(TurnContextView ctx, String traceId) {
        StringBuilder sb = new StringBuilder();
        for (ContextSection s : sections) {
            String rendered = s.render(ctx, budget);
            int tokens = budget.count(rendered);
            if (tokens > s.budget()) {
                rendered = budget.truncate(rendered, s.budget());
                log.warn("section {} 超预算被截断 {}→{} trace={}", s.name(), tokens, s.budget(), traceId);
            }
            sb.append(rendered);
        }
        return sb.toString();
    }
}
