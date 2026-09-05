package com.tutor.conversation.memory.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Pure normalization and idempotency rules for semantic facts. */
final class FactPolicy {
    static final Set<String> CATEGORIES = Set.of("goal", "preference", "skill", "constraint", "background");

    private FactPolicy() {
    }

    static String normalizeCategory(String category) {
        if (category == null) return "background";
        String value = category.trim().toLowerCase(Locale.ROOT);
        return CATEGORIES.contains(value) ? value : "background";
    }

    static String hashOf(String factText) {
        String canonical = factText == null ? "" : factText.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
