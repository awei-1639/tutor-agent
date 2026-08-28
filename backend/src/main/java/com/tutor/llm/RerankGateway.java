package com.tutor.llm;

import java.util.List;

/** 仅暴露候选文档重排能力，检索模块无需依赖对话模型。 */
public interface RerankGateway {
    double[] rerank(String query, List<String> documents, String traceId);
}
