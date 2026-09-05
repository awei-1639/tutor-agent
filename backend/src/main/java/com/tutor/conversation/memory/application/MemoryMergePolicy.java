package com.tutor.conversation.memory.application;

import com.tutor.conversation.memory.BigramSimilarity;
import com.tutor.conversation.memory.local.EpisodeStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure deterministic policy for combining local and remote memory candidates. */
final class MemoryMergePolicy {
    private static final int MAX_RECALL = 5;
    private static final double RECENCY_WEIGHT = 0.15D;
    private static final double NEAR_DUPLICATE_THRESHOLD = 0.88D;
    private final double recencyDecayDays;

    MemoryMergePolicy(double recencyDecayDays) {
        this.recencyDecayDays = Math.max(1D, recencyDecayDays);
    }

    List<EpisodeStore.Episode> safeEpisodes(List<EpisodeStore.Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        return episodes.stream()
                .filter(episode -> episode != null
                        && episode.summary() != null
                        && !episode.summary().isBlank())
                .toList();
    }

    List<EpisodeStore.Episode> merge(List<EpisodeStore.Episode> local,
                                     List<EpisodeStore.Episode> remote) {
        LinkedHashMap<String, EpisodeStore.Episode> unique = new LinkedHashMap<>();
        add(unique, local);
        add(unique, remote);
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(this::rankScore).reversed())
                .limit(MAX_RECALL)
                .toList();
    }

    private void add(LinkedHashMap<String, EpisodeStore.Episode> target,
                     List<EpisodeStore.Episode> episodes) {
        if (episodes == null) return;
        for (EpisodeStore.Episode episode : episodes) {
            if (episode == null || episode.summary() == null || episode.summary().isBlank()) continue;
            String key = BigramSimilarity.canonical(episode.summary());
            if (target.keySet().stream().noneMatch(existing ->
                    BigramSimilarity.similarity(existing, key) >= NEAR_DUPLICATE_THRESHOLD)) {
                target.putIfAbsent(key, episode);
            }
        }
    }

    private double rankScore(EpisodeStore.Episode episode) {
        double relevance = Double.isFinite(episode.relevance())
                ? Math.clamp(episode.relevance(), 0D, 1D) : 0D;
        double unresolvedBonus = episode.openItems() == null || episode.openItems().isEmpty() ? 0D : 0.05D;
        return relevance + RECENCY_WEIGHT * recencyFactor(episode.createdAt()) + unresolvedBonus;
    }

    private double recencyFactor(Instant createdAt) {
        if (createdAt == null) return 1.0D;
        long ageDays = Math.max(0L, Duration.between(createdAt, Instant.now()).toDays());
        return Math.exp(-(double) ageDays / recencyDecayDays);
    }
}
