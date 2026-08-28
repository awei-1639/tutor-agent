package com.tutor.retrieval.graph;

import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;

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
            addRule(rules, new Rule("PREREQUISITE", Direction.INCOMING, 0.80D, 1.0D));
            addRule(rules, new Rule("TEACHES", Direction.INCOMING, 0.85D, 0.90D));
        }
        if (effectiveFacets.contains(RoutingPolicy.RetrievalFacet.CAREER)) {
            addRule(rules, new Rule("REQUIRES", Direction.OUTGOING, 0.80D, 1.0D));
            addRule(rules, new Rule("LEADS_TO", Direction.OUTGOING, 0.85D, 0.85D));
        }
        return rules.isEmpty() ? none() : new GraphExpansionPolicy(List.copyOf(rules.values()), 0.15D);
    }

    private static void addRule(LinkedHashMap<String, Rule> rules, Rule rule) {
        rules.putIfAbsent(rule.relation() + ':' + rule.direction(), rule);
    }

    public static GraphExpansionPolicy of(Rule... rules) {
        return new GraphExpansionPolicy(List.of(rules), 0.15D);
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

    public record Rule(String relation, Direction direction, double minConfidence, double weight) {
        public Rule(String relation, Direction direction) {
            this(relation, direction, 0.80D, 1.0D);
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
        }

        String description() {
            return relation + (direction == Direction.OUTGOING ? "->" : "<-")
                    + "@" + String.format(Locale.ROOT, "%.2f", minConfidence);
        }
    }

    public enum Direction { OUTGOING, INCOMING }
}
