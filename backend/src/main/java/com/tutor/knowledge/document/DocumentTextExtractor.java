package com.tutor.knowledge.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 负责 PDF、DOCX、TXT 和 Markdown 的文本提取及低文本 PDF 的 OCR 补偿。 */
final class DocumentTextExtractor {
    private static final Pattern HEADING_STYLE = Pattern.compile("(?:heading|标题)([1-6])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final AliyunOcrClient ocr;

    DocumentTextExtractor(AliyunOcrClient ocr) {
        this.ocr = ocr;
    }

    String extract(byte[] bytes, String filename) {
        try {
            String normalizedFilename = filename.toLowerCase(Locale.ROOT);
            if (normalizedFilename.endsWith(".pdf")) return extractPdf(bytes);
            if (normalizedFilename.endsWith(".docx")) return extractDocx(bytes);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件解析失败，请确认文件未加密且格式正确");
        }
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            StringBuilder pages = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = ocr.enabled() ? new PDFRenderer(document) : null;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String content = stripper.getText(document).strip();
                if (ocr.enabled() && content.length() < ocr.textDensityThreshold() && page <= ocr.maxPages()) {
                    content = ocr.recognize(renderPage(renderer, page - 1));
                }
                if (!content.isBlank()) {
                    if (!pages.isEmpty()) pages.append("\n\n");
                    pages.append("[第 ").append(page).append(" 页]\n").append(content);
                }
            }
            return pages.toString().strip();
        }
    }

    private static byte[] renderPage(PDFRenderer renderer, int page) {
        try (ByteArrayOutputStream image = new ByteArrayOutputStream()) {
            ImageIO.write(renderer.renderImageWithDPI(page, 144), "png", image);
            return image.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF 页面渲染失败，无法进行 OCR", e);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph) {
                    String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
                    if (text.isBlank()) continue;
                    int level = headingLevel(paragraph.getStyle());
                    if (level > 0) out.append("#".repeat(level)).append(' ').append(text).append("\n\n");
                    else if (paragraph.getNumID() != null) out.append("- ").append(text).append('\n');
                    else out.append(text).append("\n\n");
                } else if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) appendTable(out, table);
            }
            return out.toString().strip();
        }
    }

    private static void appendTable(StringBuilder out, org.apache.poi.xwpf.usermodel.XWPFTable table) {
        int width = table.getRows().stream().mapToInt(row -> row.getTableCells().size()).max().orElse(0);
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            List<String> cells = new ArrayList<>(table.getRows().get(rowIndex).getTableCells().stream()
                    .map(cell -> cell.getText().replace("\n", " ").strip().replace("|", "\\|")).toList());
            while (cells.size() < width) cells.add("");
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (rowIndex == 0) out.append("| ").append("--- | ".repeat(width)).append("\n");
        }
        out.append('\n');
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        var matcher = HEADING_STYLE.matcher(WHITESPACE.matcher(style.toLowerCase(Locale.ROOT)).replaceAll(""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
