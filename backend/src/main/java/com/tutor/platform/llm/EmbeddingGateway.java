package com.tutor.platform.llm;

import java.util.List;

/** 向量化能力的窄接口；调用方无需依赖对话、重排等其他 LLM 能力。 */
public interface EmbeddingGateway {
    float[] embed(String text, String traceId);

    float[] embedQuery(String text, String traceId);

    List<float[]> embedBatch(List<String> texts, String traceId);
}
