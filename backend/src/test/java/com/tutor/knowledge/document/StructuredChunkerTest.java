package com.tutor.knowledge.document;

import com.tutor.conversation.context.TokenBudget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkerTest {
    private final StructuredChunker chunker = new StructuredChunker();

    @Test
    void preservesMarkdownSectionPathAndCodeBlock() {
        String markdown = """
                # RAG 指南
                ## 检索
                先创建向量索引，再进行混合检索。

                ```java
                search(query);
                ```
                """;

        var chunks = chunker.chunk(markdown, "guide.md", 20);

        assertThat(chunks).extracting(StructuredChunker.Chunk::sectionPath).contains("RAG 指南 > 检索");
        assertThat(chunks).extracting(StructuredChunker.Chunk::blockType).contains("code");
    }

    @Test
    void keepsParagraphsSeparatedForNonMarkdownDocuments() {
        var chunks = chunker.chunk("第一段包含足够的普通文字内容用于切片。\n\n第二段同样是独立内容。", "guide.docx", 20);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().blockType()).isEqualTo("paragraph");
    }

    @Test
    void keepsPdfPageMetadata() {
        var chunks = chunker.chunk("[[PAGE:2]]\n\n第二页文本内容。", "guide.pdf", 20);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().pageFrom()).isEqualTo(2);
        assertThat(chunks.getFirst().metadata()).containsEntry("pageFrom", 2);
    }

    @Test
    void retainsMarkdownTableAsTableBlock() {
        var chunks = chunker.chunk("# 技能\n\n| 名称 | 等级 |\n| --- | --- |\n| Java | 熟练 |", "guide.docx", 20);

        assertThat(chunks).extracting(StructuredChunker.Chunk::blockType).contains("table");
        assertThat(chunks).extracting(StructuredChunker.Chunk::sectionPath).contains("技能");
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.text()).contains("名称=Java", "等级=熟练"));
    }

    @Test
    void overlapsAdjacentBlocksWithinTheSameSection() {
        String first = "甲".repeat(1_800);
        String second = "乙".repeat(1_000);
        var chunks = chunker.chunk(first + "\n\n" + second, "guide.docx", 20);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).text()).startsWith("甲").contains("乙");
    }

    @Test
    void tokenAwareChunksStayWithinEmbeddingBudget() {
        StructuredChunker tokenChunker = new StructuredChunker(new TokenBudget());
        StringBuilder sourceBuilder = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            sourceBuilder.append("第").append(i).append("个句子描述背景和目标以及实施约束。");
        }

        var chunks = tokenChunker.chunk(sourceBuilder.toString(), "guide.docx", 20);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(new TokenBudget().count(chunk.text())).isLessThanOrEqualTo(2_300));
    }

    @Test
    void reportsWhenChunkLimitDropsRemainingBlocks() {
        StructuredChunker tokenChunker = new StructuredChunker(new TokenBudget());
        String source = "第一段内容。".repeat(900) + "\n\n" + "第二段内容。".repeat(900);

        var result = tokenChunker.chunkWithStatus(source, "guide.docx", 1);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }
}
