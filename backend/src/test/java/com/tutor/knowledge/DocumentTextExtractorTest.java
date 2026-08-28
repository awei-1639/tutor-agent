package com.tutor.knowledge;

import com.tutor.config.AliyunOcrProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor(
            new AliyunOcrClient(new AliyunOcrProperties(false, "", "", "", 80, 100, 15)));

    @Test
    void returnsPlainTextForTextAndMarkdownFiles() {
        assertThat(extractor.extract("  学习路径  ".getBytes(StandardCharsets.UTF_8), "path.md"))
                .isEqualTo("学习路径");
    }

    @Test
    void preservesDocxHeadingsListsAndTablesAsStructuredText() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("检索基础");
            var item = document.createParagraph();
            item.setNumID(java.math.BigInteger.ONE);
            item.createRun().setText("理解向量检索");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("概念");
            table.getRow(0).getCell(1).setText("说明");
            document.write(output);
            bytes = output.toByteArray();
        }

        String text = extractor.extract(bytes, "guide.docx");

        assertThat(text).contains("# 检索基础", "- 理解向量检索", "| 概念 | 说明 |");
    }

    @Test
    void rejectsMalformedDocx() {
        assertThatThrownBy(() -> extractor.extract("not a docx".getBytes(StandardCharsets.UTF_8), "broken.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件解析失败");
    }
}
