package com.tutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Upload admission limits. Keep these separate from parser limits so transport and CPU budgets are explicit. */
@ConfigurationProperties(prefix = "knowledge.upload")
public record KnowledgeUploadProperties(DataSize maxFileSize, DataSize maxRequestSize, int maxPerHour,
                                        DataSize multipartThreshold, DataSize multipartPartSize,
                                        Duration sessionTtl) {
    public KnowledgeUploadProperties(DataSize maxFileSize, DataSize maxRequestSize, int maxPerHour) {
        this(maxFileSize, maxRequestSize, maxPerHour, DataSize.ofMegabytes(8), DataSize.ofMegabytes(8), Duration.ofHours(24));
    }

    public KnowledgeUploadProperties(DataSize maxFileSize, DataSize maxRequestSize, int maxPerHour,
                                     DataSize multipartThreshold, DataSize multipartPartSize) {
        this(maxFileSize, maxRequestSize, maxPerHour, multipartThreshold, multipartPartSize, Duration.ofHours(24));
    }

    @ConstructorBinding
    public KnowledgeUploadProperties {
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(50) : maxFileSize;
        maxRequestSize = maxRequestSize == null ? DataSize.ofMegabytes(55) : maxRequestSize;
        multipartThreshold = multipartThreshold == null ? DataSize.ofMegabytes(8) : multipartThreshold;
        multipartPartSize = multipartPartSize == null ? DataSize.ofMegabytes(8) : multipartPartSize;
        sessionTtl = sessionTtl == null ? Duration.ofHours(24) : sessionTtl;
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("knowledge upload max-file-size must be positive");
        }
        if (maxRequestSize.toBytes() <= 0) {
            throw new IllegalArgumentException("knowledge upload max-request-size must be positive");
        }
        if (multipartThreshold.toBytes() <= 0 || multipartPartSize.toBytes() < DataSize.ofMegabytes(5).toBytes()) {
            throw new IllegalArgumentException("OSS multipart upload sizes are invalid");
        }
        if (sessionTtl.isNegative() || sessionTtl.isZero() || sessionTtl.compareTo(Duration.ofMinutes(15)) < 0) {
            throw new IllegalArgumentException("knowledge upload session TTL must be at least 15 minutes");
        }
        maxPerHour = maxPerHour <= 0 ? 20 : maxPerHour;
    }

    public long maxFileBytes() { return maxFileSize.toBytes(); }
}
