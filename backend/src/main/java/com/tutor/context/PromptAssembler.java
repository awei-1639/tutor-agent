package com.tutor.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

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
    private final ContextPlanner planner;

    public PromptAssembler(List<ContextSection> sections, TokenBudget budget, ContextPlanner planner) {
        this.sections = sections;
        this.budget = budget;
        this.planner = planner;
    }

    public String assemble(TurnContextView ctx, String traceId) {
        return assembleWithMetadata(ctx, traceId).prompt();
    }

    public record Assembled(String prompt, Set<String> citationIds) {
        public Assembled {
            citationIds = citationIds == null ? Set.of() : Set.copyOf(citationIds);
        }
    }

    /** Returns the prompt plus citation IDs that survived every context budget. */
    public Assembled assembleWithMetadata(TurnContextView ctx, String traceId) {
        ContextPlanner.Plan plan = planner.plan(sections, ctx, budget);
        plan.allocations().stream().filter(a -> a.originalTokens() > a.allocatedTokens())
                .forEach(a -> log.info("context section={} {}→{} dropped={} trace={}", a.name(), a.originalTokens(),
                        a.allocatedTokens(), a.dropped(), traceId));
        return new Assembled(plan.prompt(), plan.citationIds());
    }
}
