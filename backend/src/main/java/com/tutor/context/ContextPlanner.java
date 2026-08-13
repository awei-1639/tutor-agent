package com.tutor.context;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Single owner of system-context allocation. The gateway must not silently reorder these priorities. */
@Component
public class ContextPlanner {
    static final int SYSTEM_CONTEXT_BUDGET = 2_600;

    public record Allocation(String name, int originalTokens, int allocatedTokens, boolean dropped) {}
    public record Plan(String prompt, List<Allocation> allocations, Set<String> citationIds) {
        public Plan {
            citationIds = citationIds == null ? Set.of() : Set.copyOf(citationIds);
        }
    }

    public Plan plan(List<ContextSection> sections, TurnContextView context, TokenBudget budget) {
        record Candidate(ContextSection section, ContextSection.Rendered rendered, int tokens) {}
        List<Candidate> candidates = sections.stream()
                .map(section -> {
                    ContextSection.Rendered rendered = section.renderWithMetadata(context, budget);
                    return new Candidate(section, rendered, budget.count(rendered.text()));
                })
                .filter(candidate -> !candidate.rendered().text().isBlank())
                .sorted(Comparator.comparingInt(candidate -> priority(candidate.section().name())))
                .toList();
        int remaining = SYSTEM_CONTEXT_BUDGET;
        StringBuilder prompt = new StringBuilder();
        java.util.ArrayList<Allocation> allocations = new java.util.ArrayList<>();
        Set<String> citationIds = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            int capped = Math.min(candidate.tokens(), candidate.section().budget());
            int allocated = Math.min(capped, remaining);
            String text = allocated == 0 ? "" : budget.truncate(candidate.rendered().text(), allocated);
            prompt.append(text);
            int retainedPrefixLength = text.endsWith("…") ? text.length() - 1 : text.length();
            for (ContextSection.CitationMarker marker : candidate.rendered().citationMarkers()) {
                if (marker.endOffset() <= retainedPrefixLength) citationIds.add(marker.id());
            }
            remaining -= allocated;
            allocations.add(new Allocation(candidate.section().name(), candidate.tokens(), allocated, allocated == 0));
        }
        return new Plan(prompt.toString(), List.copyOf(allocations), citationIds);
    }

    private int priority(String section) {
        return switch (section) {
            case "rules" -> 0;
            case "evidence" -> 1;
            case "profile" -> 2;
            case "episodes" -> 3;
            case "summary" -> 4;
            default -> 5;
        };
    }
}
