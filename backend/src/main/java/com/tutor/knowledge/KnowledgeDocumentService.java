package com.tutor.knowledge;

import com.tutor.llm.LlmGateway;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.retrieval.vector.VectorStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 管理员知识库文档：OSS 存原文件，异步解析/切分/向量化，完成后纳入 RAG 检索。 */
@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final int MAX_TEXT_CHARS = 500_000;
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 120;
    private static final int MAX_CHUNKS = 300;

    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final LlmGateway gateway;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public KnowledgeDocumentService(JdbcTemplate jdbc, OssStorage oss, LlmGateway gateway) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.gateway = gateway;
    }

    @PreDestroy
    void shutdownExecutor() {
        ExecutorLifecycle.shutdown(executor, "knowledge-document", log);
    }

    public record UploadResult(String id, String status, boolean deduplicated) {}

    public UploadResult upload(long adminId, MultipartFile file, String requestedTitle) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文档");
        }
        String filename = safeFilename(file.getOriginalFilename());
        requireSupported(filename);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        String hash = sha256(bytes);
        List<UUID> duplicated = jdbc.query("""
                SELECT id FROM knowledge_documents
                WHERE content_hash=? AND deleted_at IS NULL AND status <> 'failed'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), hash);
        if (!duplicated.isEmpty()) return new UploadResult(duplicated.getFirst().toString(), "deduplicated", true);

        UUID id = UUID.randomUUID();
        String title = requestedTitle == null || requestedTitle.isBlank() ? titleFrom(filename) : requestedTitle.trim();
        String objectKey = oss.documentKey(id, filename);
        try {
            oss.put(objectKey, bytes, file.getContentType());
            jdbc.update("""
                    INSERT INTO knowledge_documents
                    (id, title, original_filename, content_type, size_bytes, content_hash, oss_object_key, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'uploaded', ?)
                    """, id, title, filename, file.getContentType(), bytes.length, hash, objectKey, adminId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文档上传到 OSS 失败，请检查 OSS 配置和权限");
        }
        audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOADED", id);
        queue(id, bytes, filename);
        return new UploadResult(id.toString(), "uploaded", false);
    }

    public List<Map<String, Object>> list(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT d.id, d.title, d.original_filename, d.content_type, d.size_bytes, d.status,
                       d.error_message, d.chunk_count, d.created_at, d.updated_at, d.deleted_at, u.name AS creator_name
                FROM knowledge_documents d JOIN users u ON u.id=d.created_by
                ORDER BY d.created_at DESC LIMIT ?
                """, (rs, rowNum) -> row(rs), safeLimit);
    }

    public void retry(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已删除文档不能重试");
        String filename = (String) document.get("original_filename");
        String objectKey = (String) document.get("oss_object_key");
        byte[] content = oss.get(objectKey);
        jdbc.update("UPDATE knowledge_documents SET status='uploaded', error_message=NULL, updated_at=now() WHERE id=?", id);
        audit(adminId, "KNOWLEDGE_DOCUMENT_RETRIED", id);
        queue(id, content, filename);
    }

    public void softDelete(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) return;
        oss.delete((String) document.get("oss_object_key"));
        jdbc.update("DELETE FROM knowledge_document_chunks WHERE document_id=?", id);
        jdbc.update("UPDATE knowledge_documents SET status='deleted', deleted_at=now(), updated_at=now() WHERE id=?", id);
        audit(adminId, "KNOWLEDGE_DOCUMENT_SOFT_DELETED", id);
    }

    private void queue(UUID id, byte[] content, String filename) {
        executor.submit(() -> process(id, content, filename));
    }

    private void process(UUID id, byte[] content, String filename) {
        try {
            jdbc.update("UPDATE knowledge_documents SET status='processing', error_message=NULL, updated_at=now() WHERE id=?", id);
            String text = extractText(content, filename);
            if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
            List<String> chunks = split(text);
            if (chunks.isEmpty()) throw new IllegalArgumentException("文档未解析出有效文本");
            jdbc.update("DELETE FROM knowledge_document_chunks WHERE document_id=?", id);
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                float[] embedding = gateway.embed(chunk, "doc-" + id + "-" + index);
                jdbc.update("""
                        INSERT INTO knowledge_document_chunks (document_id, chunk_index, chunk_text, content_hash, embedding)
                        VALUES (?, ?, ?, ?, ?::vector)
                        """, id, index, chunk, sha256(chunk.getBytes(StandardCharsets.UTF_8)), VectorStore.toVectorLiteral(embedding));
            }
            jdbc.update("""
                    UPDATE knowledge_documents SET status='indexed', chunk_count=?, error_message=NULL, updated_at=now()
                    WHERE id=?
                    """, chunks.size(), id);
            log.info("知识文档已入库 document={} chunks={}", id, chunks.size());
        } catch (Exception e) {
            log.warn("知识文档入库失败 document={}: {}", id, e.getMessage());
            jdbc.update("UPDATE knowledge_documents SET status='failed', error_message=?, updated_at=now() WHERE id=?",
                    safeMessage(e), id);
        }
    }

    private Map<String, Object> document(UUID id) {
        try {
            return jdbc.queryForMap("SELECT id, original_filename, oss_object_key, deleted_at FROM knowledge_documents WHERE id=?", id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
    }

    private void audit(long adminId, String action, UUID documentId) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, metadata) VALUES (?, ?, ?::jsonb)",
                adminId, action, "{\"documentId\":\"" + documentId + "\"}");
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id").toString());
        row.put("title", rs.getString("title"));
        row.put("filename", rs.getString("original_filename"));
        row.put("contentType", rs.getString("content_type"));
        row.put("sizeBytes", rs.getLong("size_bytes"));
        row.put("status", rs.getString("status"));
        row.put("error", rs.getString("error_message"));
        row.put("chunkCount", rs.getInt("chunk_count"));
        row.put("creatorName", rs.getString("creator_name"));
        row.put("createdAt", String.valueOf(rs.getObject("created_at")));
        row.put("updatedAt", String.valueOf(rs.getObject("updated_at")));
        row.put("deletedAt", rs.getObject("deleted_at") == null ? null : String.valueOf(rs.getObject("deleted_at")));
        return row;
    }

    private static String extractText(byte[] bytes, String filename) {
        try {
            String normalizedFilename = filename.toLowerCase(Locale.ROOT);
            if (normalizedFilename.endsWith(".pdf")) {
                try (var document = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(document).strip(); }
            }
            if (normalizedFilename.endsWith(".docx")) {
                try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
                     var extractor = new XWPFWordExtractor(document)) { return extractor.getText().strip(); }
            }
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件解析失败，请确认文件未加密且格式正确");
        }
    }

    private static List<String> split(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n{3,}", "\n\n").strip();
        if (text.length() < 20) return List.of();
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length() && out.size() < MAX_CHUNKS;) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf("\n\n", end), text.lastIndexOf('。', end));
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1;
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) out.add(chunk);
            if (end >= text.length()) break;
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return out;
    }

    private static void requireSupported(String filename) {
        String normalizedFilename = filename.toLowerCase(Locale.ROOT);
        if (!(normalizedFilename.endsWith(".pdf") || normalizedFilename.endsWith(".docx")
                || normalizedFilename.endsWith(".txt") || normalizedFilename.endsWith(".md"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PDF、DOCX、TXT、Markdown 文档");
        }
    }

    private static String safeFilename(String original) {
        String file = original == null ? "document.txt" : original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return file.isBlank() ? "document.txt" : file;
    }

    private static String titleFrom(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String sha256(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (Exception e) { throw new IllegalStateException("无法计算文档摘要", e); }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? "文档处理失败" : message.substring(0, Math.min(message.length(), 500));
    }
}
