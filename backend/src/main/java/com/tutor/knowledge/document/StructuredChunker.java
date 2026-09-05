package com.tutor.knowledge.document;

import com.tutor.conversation.context.TokenBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将支持的文档文本切成感知章节的分块，必要时退回到有界窗口切分。 */
@Component
public class StructuredChunker {
    /** 生产环境以 Token 限制为准；字符限制仅作为无 Token 计数时的回退。 */
    private static final int TARGET_TOKENS = 1_500;
    private static final int MAX_TOKENS = 2_300;
    private static final int OVERLAP_TOKENS = 200;
    private static final int FALLBACK_TARGET_CHARS = 2_400;
    private static final int FALLBACK_MAX_CHARS = 3_600;
    private static final int FALLBACK_OVERLAP_CHARS = 240;

    private final TokenBudget tokenBudget;

    public StructuredChunker() {
        this(null);
    }

    @Autowired
    public StructuredChunker(TokenBudget tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public record Chunk(String text, String sectionPath, String blockType, Integer pageFrom, Integer pageTo,
                        Map<String, Object> metadata) {}
    public record ChunkingResult(List<Chunk> chunks, boolean truncated) {}
    private record Block(String text, String sectionPath, String type, Integer pageFrom, Integer pageTo) {}

    public List<Chunk> chunk(String text, String filename, int maxChunks) {
        return chunkWithStatus(text, filename, maxChunks).chunks();
    }

    public ChunkingResult chunkWithStatus(String text, String filename, int maxChunks) {
        List<Block> blocks = filename.toLowerCase().endsWith(".md") || hasMarkdownHeadings(text)
                ? markdownBlocks(text) : paragraphBlocks(text);
        blocks = blocks.stream().filter(block -> !block.text().isBlank()).toList();
        List<Chunk> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentPath = "";
        String currentType = "paragraph";
        Integer currentPageFrom = null;
        Integer currentPageTo = null;
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            Block block = blocks.get(blockIndex);
            if (out.size() >= maxChunks) return new ChunkingResult(out, true);
            boolean incompatible = !currentPath.equals(block.sectionPath()) || !currentType.equals(block.type())
                    || !java.util.Objects.equals(currentPageFrom, block.pageFrom());
            String candidate = buffer + (buffer.isEmpty() ? "" : "\n\n") + block.text();
            boolean exceedsTarget = tokenBudget != null
                    ? tokenBudget.count(candidate) > TARGET_TOKENS
                    : candidate.length() > FALLBACK_TARGET_CHARS;
            if (!buffer.isEmpty() && (incompatible || exceedsTarget)) {
                // 仅当下一块仍在同一结构章节时保留少量尾部文本。
                // 章节或页面切换时，不应让文本跨越边界泄漏。
                String overlap = !incompatible ? overlapTail(buffer) : "";
                flush(out, buffer, currentPath, currentType, currentPageFrom, currentPageTo, maxChunks);
                if (out.size() >= maxChunks) return new ChunkingResult(out, true);
                if (!overlap.isBlank()) buffer.append(overlap).append("\n\n");
            }
            currentPath = block.sectionPath();
            currentType = block.type();
            currentPageFrom = block.pageFrom();
            currentPageTo = block.pageTo();
            if (exceedsBlockLimit(block.text())) {
                buffer.setLength(0);
                if (splitLongBlock(out, block, maxChunks)) return new ChunkingResult(out, true);
            } else {
                if (!buffer.isEmpty()) buffer.append("\n\n");
                buffer.append(block.text());
            }
        }
        flush(out, buffer, currentPath, currentType, currentPageFrom, currentPageTo, maxChunks);
        return new ChunkingResult(out, false);
    }

    private static List<Block> markdownBlocks(String source) {
        List<Block> out = new ArrayList<>();
        String[] lines = normalize(source).split("\n", -1);
        String[] headings = new String[6];
        StringBuilder current = new StringBuilder();
        String type = "paragraph";
        for (String line : lines) {
            int level = headingLevel(line);
            if (level > 0) {
                addBlock(out, current, path(headings), type, null, null);
                headings[level - 1] = line.substring(level).strip();
                for (int i = level; i < headings.length; i++) headings[i] = null;
                type = "heading";
                current.append(line.strip());
            } else if (line.startsWith("```") && !current.isEmpty()) {
                addBlock(out, current, path(headings), type, null, null);
                type = "code";
                current.append(line).append('\n');
            } else {
                if (current.toString().startsWith("```")) type = "code";
                else if (line.strip().startsWith("|") && line.strip().endsWith("|")) type = "table";
                else if (line.strip().startsWith("-") || line.strip().matches("\\d+\\..*")) type = "list";
                current.append(line).append('\n');
                if (line.startsWith("```") && current.length() > 4) {
                    addBlock(out, current, path(headings), type, null, null);
                    type = "paragraph";
                }
            }
        }
        addBlock(out, current, path(headings), type, null, null);
        return out;
    }

    private static List<Block> paragraphBlocks(String source) {
        List<Block> out = new ArrayList<>();
        int page = 1;
        for (String part : normalize(source).split("\n\\s*\n")) {
            String value = part.strip();
            if (value.matches("\\[\\[PAGE:\\d+]]")) {
                page = Integer.parseInt(value.substring(7, value.length() - 2));
            } else if (!value.isBlank()) out.add(new Block(value, "", "paragraph", page, page));
        }
        return out;
    }

    private boolean splitLongBlock(List<Chunk> out, Block block, int maxChunks) {
        String value = block.text();
        int start = 0;
        for (; start < value.length() && out.size() < maxChunks;) {
            int end = tokenBudget == null
                    ? Math.min(value.length(), start + FALLBACK_MAX_CHARS)
                    : value.length();
            end = fitTokenLimit(value, start, end);
            if (end < value.length()) {
                int boundary = sentenceBoundary(value, start, end);
                int targetBoundary = tokenBudget == null
                        ? start + FALLBACK_TARGET_CHARS / 2 : start + 1;
                if (boundary > targetBoundary) {
                    int candidateEnd = boundary + 1;
                    if (tokenBudget == null || tokenBudget.count(value.substring(start, candidateEnd)) <= MAX_TOKENS) {
                        end = candidateEnd;
                    }
                }
            }
            add(out, value.substring(start, end).strip(), block.sectionPath(), block.type(), block.pageFrom(), block.pageTo(), maxChunks);
            if (end >= value.length()) break;
            int nextStart = tokenBudget == null
                    ? Math.max(start + 1, end - FALLBACK_OVERLAP_CHARS)
                    : Math.max(start + 1, end - tokenBudget.suffix(value.substring(start, end), OVERLAP_TOKENS).length());
            start = nextStart;
        }
        return start < value.length();
    }

    private boolean exceedsBlockLimit(String text) {
        return tokenBudget != null ? tokenBudget.count(text) > MAX_TOKENS : text.length() > FALLBACK_MAX_CHARS;
    }

    private int fitTokenLimit(String value, int start, int end) {
        if (tokenBudget == null) return end;
        if (tokenBudget.count(value.substring(start, end)) <= MAX_TOKENS) return end;
        int lo = start + 1;
        int hi = end;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (tokenBudget.count(value.substring(start, mid)) <= MAX_TOKENS) lo = mid;
            else hi = mid - 1;
        }
        return Math.max(start + 1, lo);
    }

    private static int sentenceBoundary(String value, int start, int end) {
        int limit = Math.min(end, value.length() - 1);
        for (int i = limit; i > start; i--) {
            char c = value.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';' || c == '\n') {
                return i;
            }
            if (c == '.' && !(i > 0 && i + 1 < value.length()
                    && Character.isDigit(value.charAt(i - 1)) && Character.isDigit(value.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private String overlapTail(StringBuilder buffer) {
        if (tokenBudget != null) return tokenBudget.suffix(buffer.toString(), OVERLAP_TOKENS).strip();
        int start = Math.max(0, buffer.length() - FALLBACK_OVERLAP_CHARS);
        return buffer.substring(start).strip();
    }

    private static void flush(List<Chunk> out, StringBuilder buffer, String path, String type, Integer pageFrom, Integer pageTo, int maxChunks) {
        if (!buffer.isEmpty()) add(out, buffer.toString().strip(), path, type, pageFrom, pageTo, maxChunks);
        buffer.setLength(0);
    }

    private static void add(List<Chunk> out, String text, String path, String type, Integer pageFrom, Integer pageTo, int maxChunks) {
        if (text.isBlank() || out.size() >= maxChunks) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sectionPath", path);
        metadata.put("blockType", type);
        if (pageFrom != null) metadata.put("pageFrom", pageFrom);
        if (pageTo != null) metadata.put("pageTo", pageTo);
        out.add(new Chunk(text, path, type, pageFrom, pageTo, Map.copyOf(metadata)));
    }

    private static void addBlock(List<Block> out, StringBuilder value, String path, String type, Integer pageFrom, Integer pageTo) {
        String text = value.toString().strip();
        if ("table".equals(type)) text = normalizeTable(text);
        if (!text.isBlank()) out.add(new Block(text, path, type, pageFrom, pageTo));
        value.setLength(0);
    }

    /** 将 Markdown 表格转换为可检索的字段/值陈述。 */
    private static String normalizeTable(String source) {
        String[] rows = source.lines().map(String::strip)
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .toArray(String[]::new);
        if (rows.length < 2) return source;
        List<String> headers = tableCells(rows[0]);
        StringBuilder out = new StringBuilder("表格字段: ").append(String.join("、", headers)).append('\n');
        for (int i = 1; i < rows.length; i++) {
            List<String> cells = tableCells(rows[i]);
            if (cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"))) continue;
            out.append("记录: ");
            for (int c = 0; c < cells.size(); c++) {
                if (c > 0) out.append("；");
                String key = c < headers.size() && !headers.get(c).isBlank() ? headers.get(c) : "列" + (c + 1);
                out.append(key).append('=').append(cells.get(c));
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    private static List<String> tableCells(String row) {
        String inner = row.substring(1, row.length() - 1);
        return java.util.Arrays.stream(inner.split("\\s*\\|\\s*", -1))
                .map(String::strip).toList();
    }

    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') count++;
        return count > 0 && count <= 6 && count < line.length() && Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    private static boolean hasMarkdownHeadings(String text) {
        return normalize(text).lines().anyMatch(line -> headingLevel(line) > 0);
    }

    private static String path(String[] headings) {
        List<String> values = new ArrayList<>();
        for (String heading : headings) if (heading != null && !heading.isBlank()) values.add(heading);
        return String.join(" > ", values);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n{3,}", "\n\n").strip();
    }
}
