package com.tutor.llm;

import com.tutor.config.LlmProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;
import java.util.List;

/** Embedding Provider 适配器，只封装模型 SDK 初始化和向量请求。 */
final class EmbeddingProviderClient {
    private final EmbeddingModel model;

    EmbeddingProviderClient(LlmProperties properties) {
        this.model = OpenAiEmbeddingModel.builder()
                .apiKey(properties.siliconflow().apiKey())
                .baseUrl(properties.siliconflow().baseUrl())
                .modelName(properties.routing().get("embed"))
                .timeout(Duration.ofSeconds(30))
                // 重试由网关统一负责，避免隐式 SDK 重试循环放大预算记账。
                .maxRetries(0)
                .build();
    }

    float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    List<float[]> embedBatch(List<String> texts) {
        List<Embedding> embeddings = model.embedAll(texts.stream().map(TextSegment::from).toList()).content();
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new IllegalStateException("embedding provider returned mismatched batch size");
        }
        return embeddings.stream().map(Embedding::vector).toList();
    }
}
