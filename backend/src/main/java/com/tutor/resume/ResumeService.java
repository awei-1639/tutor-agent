package com.tutor.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import com.tutor.retrieval.VectorStore;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 简历处理管线 (实现设计 3.4/8.1): 解析 → 本地PII脱敏 → 原文+映射加密留存 →
 * LLM结构化(只见脱敏文本) → 脱敏文本embedding → 落库。同步执行, 失败明确报错。
 */
@Service
public class ResumeService {
    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final String STRUCT_SYS = """
            你是简历结构化抽取器。输入为已脱敏的简历文本(含[NAME_1]等占位符, 原样保留)。输出严格JSON:
            {"education":[{"school":"","degree":"","major":"","period":""}],
             "experiences":[{"company":"","title":"","period":"","highlights":["要点"]}],
             "projects":[{"name":"","role":"","description":"","tech":["技术"]}],
             "skills":["技能名"],
             "summary":"50字以内的画像式概括"}
            只抽取文本中真实存在的信息, 缺失字段给空串/空数组, 禁止编造。
            """;

    private final JdbcTemplate jdbc;
    private final LlmGateway gateway;
    private final com.tutor.profile.ProfileService profileService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${security.resume-enc-key:}")
    String encKey;

    public ResumeService(JdbcTemplate jdbc, LlmGateway gateway,
                         com.tutor.profile.ProfileService profileService) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.profileService = profileService;
    }

    public record UploadResult(long resumeId, JsonNode structured, int maskedPiiCount) {}

    public UploadResult upload(long userId, MultipartFile file) {
        if (encKey == null || encKey.isBlank()) {
            throw new IllegalStateException("RESUME_ENC_KEY 未配置, 拒绝处理简历 (安全红线: 原文必须加密存储)");
        }
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String text = extractText(file);
        if (text.strip().length() < 100) {
            throw new IllegalArgumentException("简历文本过短(" + text.strip().length() + "字), 请检查文件内容");
        }

        // 1) 本地脱敏 — 在任何外呼(LLM/embedding)之前
        PiiMasker.MaskResult masked = PiiMasker.mask(text);

        // 2) LLM结构化 (只见脱敏文本)
        String structJson = gateway.chatJson(Purpose.EXTRACT, List.of(
                SystemMessage.from(STRUCT_SYS),
                UserMessage.from(masked.masked())), traceId);
        JsonNode structured;
        try {
            structured = mapper.readTree(structJson);
        } catch (Exception e) {
            throw new IllegalStateException("简历结构化失败, 请稍后重试");
        }

        // 3) 脱敏文本embedding (外呼同样不见PII)
        float[] vec = gateway.embed(masked.masked(), traceId);

        // 4) 加密落库 (pgcrypto); 多次上传保留历史版本, 读取端总取最新
        Long resumeId = jdbc.queryForObject("""
                INSERT INTO resumes (user_id, raw_encrypted, structured, embedding)
                VALUES (?, pgp_sym_encrypt(?, ?), ?::jsonb, ?::vector) RETURNING id
                """, Long.class, userId, text, encKey, structJson, VectorStore.toVectorLiteral(vec));
        try {
            jdbc.update("INSERT INTO pii_mappings (user_id, mapping_encrypted) VALUES (?, pgp_sym_encrypt(?, ?))",
                    userId, mapper.writeValueAsString(masked.mapping()), encKey);
        } catch (Exception e) {
            throw new IllegalStateException("PII映射保存失败", e);
        }
        // 5) 简历技能回填画像 (explicit来源; 失败不影响上传)
        java.util.List<String> skillNames = new java.util.ArrayList<>();
        structured.path("skills").forEach(s -> skillNames.add(s.asText()));
        profileService.mergeResumeSkills(userId, skillNames);

        log.info("简历入库 user={} resume={} pii={} trace={}", userId, resumeId, masked.mapping().size(), traceId);
        return new UploadResult(resumeId, structured, masked.mapping().size());
    }

    /** 最新简历的结构化紧凑文本 (专家简报注入用, 实现设计3.4分级注入); 无简历返回空串 */
    public String latestStructuredCompact(long userId, int maxChars) {
        List<String> rows = jdbc.query(
                "SELECT structured::text FROM resumes WHERE user_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), userId);
        if (rows.isEmpty()) return "";
        try {
            JsonNode s = mapper.readTree(rows.get(0));
            StringBuilder sb = new StringBuilder("## 简历(结构化)\n");
            if (s.hasNonNull("summary")) sb.append(s.get("summary").asText()).append('\n');
            for (JsonNode e : s.path("experiences")) {
                sb.append("经历: ").append(e.path("company").asText()).append(' ')
                        .append(e.path("title").asText()).append(' ').append(e.path("period").asText()).append('\n');
            }
            for (JsonNode p : s.path("projects")) {
                sb.append("项目: ").append(p.path("name").asText()).append(" — ")
                        .append(p.path("description").asText()).append('\n');
            }
            JsonNode skills = s.path("skills");
            if (skills.isArray() && !skills.isEmpty()) {
                sb.append("技能: ");
                skills.forEach(k -> sb.append(k.asText()).append(','));
                sb.append('\n');
            }
            String out = sb.toString();
            return out.length() > maxChars ? out.substring(0, maxChars) + "…" : out;
        } catch (Exception e) {
            return "";
        }
    }

    private String extractText(MultipartFile file) {
        String name = String.valueOf(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".pdf")) {
                try (var doc = Loader.loadPDF(bytes)) {
                    return new PDFTextStripper().getText(doc);
                }
            }
            if (name.endsWith(".docx")) {
                try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                     var ex = new XWPFWordExtractor(doc)) {
                    return ex.getText();
                }
            }
            if (name.endsWith(".txt") || name.endsWith(".md")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            throw new IllegalArgumentException("仅支持 PDF / DOCX / TXT 格式, 收到: " + name);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("简历解析失败 {}: {}", name, e.getMessage());
            throw new IllegalArgumentException("文件解析失败, 请确认文件未加密且格式正确");
        }
    }
}
