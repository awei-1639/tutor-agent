package com.tutor.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** 知识文档的文件名、格式签名和文本安全校验。 */
final class KnowledgeDocumentFilePolicy {
    private KnowledgeDocumentFilePolicy() {}

    static void requireSupported(String filename) {
        String normalizedFilename = filename.toLowerCase(Locale.ROOT);
        if (!(normalizedFilename.endsWith(".pdf") || normalizedFilename.endsWith(".docx")
                || normalizedFilename.endsWith(".txt") || normalizedFilename.endsWith(".md"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PDF、DOCX、TXT、Markdown 文档");
        }
    }

    /** 允许的 resource_kind 与检索通道语义对齐: document=知识讲解, resource=课程/书目清单, job=岗位资料。 */
    static final java.util.Set<String> RESOURCE_KINDS = java.util.Set.of("document", "resource", "job");

    /** 空值返回 null (调用方落库默认 'document'); 非法值显式拒绝, 不静默纠正。 */
    static String sanitizeResourceKind(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        if (!RESOURCE_KINDS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "resourceKind 仅支持 document、resource、job");
        }
        return normalized;
    }

    /** 扩展名仅用于体验提示；在进入解析器或 OSS 前拒绝常见的二进制伪装文件。 */
    static void requireContentMatchesExtension(String filename, byte[] bytes) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".pdf") && !startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 文件签名无效");
        }
        if (normalized.endsWith(".docx") && !isDocxPackage(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DOCX 文件结构无效");
        }
        if ((normalized.endsWith(".txt") || normalized.endsWith(".md")) && isLikelyBinary(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本文件包含非文本二进制内容");
        }
    }

    static String safeFilename(String original) {
        String file = original == null ? "document.txt" : original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return file.isBlank() ? "document.txt" : file;
    }

    static String titleFrom(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算文档摘要", e);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static boolean isDocxPackage(byte[] bytes) {
        if (!startsWith(bytes, new byte[]{'P', 'K', 3, 4})) return false;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            boolean contentTypes = false;
            boolean document = false;
            java.util.zip.ZipEntry entry;
            int entries = 0;
            long expanded = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2_000) return false;
                if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                if ("word/document.xml".equals(entry.getName())) document = true;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > 20L * 1024 * 1024) return false;
                }
                if (contentTypes && document) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLikelyBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        int controls = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0) return true;
            if (b < 0x09 || (b > 0x0d && b < 0x20)) controls++;
        }
        return sample > 0 && controls * 100 > sample * 5;
    }
}
