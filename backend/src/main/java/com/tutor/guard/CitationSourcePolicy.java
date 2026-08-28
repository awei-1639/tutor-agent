package com.tutor.guard;

import com.tutor.contract.Evidence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** 展示来源溯源信息的统一策略，不在请求期间抓取 URL。 */
public final class CitationSourcePolicy {
    private static final Set<String> INGESTION_STATUSES = Set.of("managed", "verified", "unverified");

    private CitationSourcePolicy() {}

    public static Provenance inspect(Evidence evidence) {
        String evidenceHash = sha256(evidence == null ? null : evidence.chunkText());
        if (evidence == null) return new Provenance("", "missing", evidenceHash);
        String safeUrl = safeUrl(evidence.sourceUrl());
        if (evidence.sourceUrl() == null || evidence.sourceUrl().isBlank()) {
            return new Provenance("", "missing", evidenceHash);
        }
        if (safeUrl.isBlank()) return new Provenance("", "invalid", evidenceHash);

        String storedHash = evidence.contentHash();
        if (storedHash != null && !storedHash.isBlank() && !evidenceHash.equalsIgnoreCase(storedHash)) {
            return new Provenance(safeUrl, "integrity_mismatch", evidenceHash);
        }
        String requestedStatus = evidence.sourceStatus() == null ? "" : evidence.sourceStatus().toLowerCase(Locale.ROOT);
        if (!INGESTION_STATUSES.contains(requestedStatus)) requestedStatus = "unverified";
        String scheme = URI.create(safeUrl).getScheme().toLowerCase(Locale.ROOT);
        if ("knowledge".equals(scheme)) {
            boolean managed = "managed".equals(requestedStatus) && storedHash != null && !storedHash.isBlank();
            return new Provenance(safeUrl, managed ? "managed" : "unverified", evidenceHash);
        }
        boolean verifiedSnapshot = "verified".equals(requestedStatus)
                && storedHash != null && !storedHash.isBlank();
        return new Provenance(safeUrl, verifiedSnapshot ? "verified" : "unverified", evidenceHash);
    }

    public static String safeUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return "";
        try {
            URI uri = URI.create(sourceUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return "";
            if ("https".equalsIgnoreCase(scheme) && uri.getHost() != null) return uri.normalize().toString();
            if ("knowledge".equalsIgnoreCase(scheme) && uri.getHost() != null) return uri.normalize().toString();
            return "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Provenance(String sourceUrl, String sourceStatus, String evidenceHash) {}
}
