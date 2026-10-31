package com.tutor.knowledge.retrieval.graph;

import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性的图扩展策略。LLM 可以建议意图，但绝不能选择任意关系类型或方向。
 */
public record GraphExpansionPolicy(List<Rule> rules, double minSourceScore) {
    private static final Set<String> ALLOWED_RELATIONS = Set.of(
            "PREREQUISITE", "TEACHES", "LEADS_TO", "REQUIRES");

    public GraphExpansionPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (!Double.isFinite(minSourceScore) || minSourceScore < 0D || minSourceScore > 1D) {
            throw new IllegalArgumentException("minSourceScore 必须在0和1之间");
        }
    }

    public static GraphExpansionPolicy none() {
        return new GraphExpansionPolicy(List.of(), 0.15D);
    }

    /** 将已审计的执行 facet 映射为固定关系集合；此处不允许重新解析原始查询。 */
    public static GraphExpansionPolicy forFacets(List<RoutingPolicy.RetrievalFacet> facets,
                                                 IntentRouter.RetrievalHint hint) {
        List<RoutingPolicy.RetrievalFacet> effectiveFacets = facets == null ? List.of() : facets.stream()
                .filter(facet -> facet != null).distinct().toList();
        if (hint == IntentRouter.RetrievalHint.NONE || effectiveFacets.isEmpty()) {
            return none();
        }
        LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
        if (effectiveFacets.contains(RoutingPolicy.RetrievalFacet.RESOURCE)) {
            addRule(rules, new Rule("TEACHES", Direction.INCOMING, 0.85D, 1.0D));
        }
        if (effectiveFacets.contains(RoutingPolicy.RetrievalFacet.LEARNING)) {
            // PREREQUISITE 入边加权 2.0 (multi_hop 诊断 2026-10-30): 学习意图下, 前置技能就是
            // 问题的正主, 但它的 chunk 与查询词面无关, 单靠 RRF 基础分 (~alpha/(k+rank)≈0.06)
            // 压不过双通道话题切片 (0.09+)。边类型本身编码了意图, 属于机制证据而非词面启发;
            // 收益/风险由全量评测裁决 (四切片防退化)。
            //
            // 两跳 (multi_hop 二轮诊断 2026-10-31): 评测集 gold 标的是前置关系的传递闭包——
            // 「学 PyTorch 先会什么」的 gold 含 neural-networks (直接前置) 和 deep-learning-basics
            // (前置的前置)。单跳只覆盖前者, 剩余缺口有 19.9% 的 gold 连 top20 都进不来, 且
            // 全是 python-basics / calculus / gradient-descent 这类最底层基础。图里两跳可达性
            // 已验证 (pytorch/data-augmentation 的两跳闭包与 gold 完全一致), 平均 3.04 个邻居,
            // 规模可控。第二跳在 GraphStore 里按 confidence 打折, 让直接前置始终排在前面。
            addRule(rules, new Rule("PREREQUISITE", Direction.INCOMING, 0.80D, 2.0D, 2));
            addRule(rules, new Rule("TEACHES", Direction.INCOMING, 0.85D, 0.90D));
        }
        if (effectiveFacets.contains(RoutingPolicy.RetrievalFacet.CAREER)) {
            addRule(rules, new Rule("REQUIRES", Direction.OUTGOING, 0.80D, 1.0D));
            addRule(rules, new Rule("LEADS_TO", Direction.OUTGOING, 0.85D, 0.85D));
        }
        return rules.isEmpty() ? none() : new GraphExpansionPolicy(List.copyOf(rules.values()), 0.15D);
    }

    private static void addRule(LinkedHashMap<String, Rule> rules, Rule rule) {
        rules.putIfAbsent(rule.relation() + ':' + rule.direction() + ':' + rule.maxHops(), rule);
    }

    public static GraphExpansionPolicy of(Rule... rules) {
        return new GraphExpansionPolicy(List.of(rules), 0.15D);
    }

    /**
     * 岗位需求清单的定向查询策略：只认 Job-[:REQUIRES]->Skill 这一条出边。
     *
     * <p>与 {@link #forFacets} 分开，是因为它的用途不同：facet 策略决定「用什么关系扩召回」，
     * 这里只用于把一个已经命中的岗位节点翻译成它的技能集合（答案形态转换），
     * 候选排序仍然由 RRF 决定。关系类型同样受 {@link #ALLOWED_RELATIONS} 白名单约束，
     * 且不读任何 LLM 输出。
     */
    public static GraphExpansionPolicy jobRequirementLookup() {
        return of(new Rule("REQUIRES", Direction.OUTGOING, 0.80D, 1.0D));
    }

    public List<String> relationDescriptions() {
        return rules.stream().map(rule -> rule.relation()
                + (rule.direction() == Direction.OUTGOING ? "->" : "<-" )).toList();
    }

    public List<String> policyDescriptions() {
        return rules.stream().map(Rule::description).toList();
    }

    public Rule ruleFor(String relation, Direction direction) {
        return rules.stream()
                .filter(rule -> rule.relation().equals(relation) && rule.direction() == direction)
                .findFirst().orElse(null);
    }

    public boolean enabled() {
        return !rules.isEmpty();
    }

    /**
     * 单条扩展规则。{@code maxHops} 是安全边界的一部分（直接写在代码里，不开放配置）：
     * 目前只允许 1（直接邻居）或 2（两跳闭包），且只有 PREREQUISITE 这类"传递性前置"语义
     * 才值得走到第二跳。
     */
    public record Rule(String relation, Direction direction, double minConfidence, double weight, int maxHops) {
        public Rule(String relation, Direction direction) {
            this(relation, direction, 0.80D, 1.0D, 1);
        }

        public Rule(String relation, Direction direction, double minConfidence, double weight) {
            this(relation, direction, minConfidence, weight, 1);
        }

        public Rule {
            relation = relation == null ? "" : relation.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_RELATIONS.contains(relation)) {
                throw new IllegalArgumentException("unsupported graph relation: " + relation);
            }
            direction = direction == null ? Direction.OUTGOING : direction;
            if (!Double.isFinite(minConfidence) || minConfidence < 0D || minConfidence > 1D) {
                throw new IllegalArgumentException("minConfidence must be between 0 and 1");
            }
            if (!Double.isFinite(weight) || weight <= 0D || weight > 2D) {
                throw new IllegalArgumentException("weight must be between 0 and 2");
            }
            if (maxHops < 1 || maxHops > 2) {
                throw new IllegalArgumentException("maxHops must be 1 or 2");
            }
        }

        String description() {
            return relation + (direction == Direction.OUTGOING ? "->" : "<-")
                    + "@" + String.format(Locale.ROOT, "%.2f", minConfidence)
                    + (maxHops > 1 ? "/" + maxHops + "hop" : "");
        }
    }

    public enum Direction { OUTGOING, INCOMING }
}
