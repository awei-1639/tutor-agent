package com.tutor.contract;

/** RAG 召回证据, [S#] 引用的数据源 (实现设计 3.1 区4) */
public record Evidence(
        String nodeId,
        String nodeType,      // skill / resource / job / company
        String chunkText,
        double score,         // RRF 融合分
        String graphPath,     // 一跳关系摘要, 可为 null
        String sourceUrl,     // 原始材料链接, 可为 null
        String sourceStatus,  // managed / verified / unverified / missing
        String contentHash    // 入库时对当前证据文本计算的 SHA-256, 可为 null
) {
    /** 兼容没有来源元数据的旧检索结果和测试构造。 */
    public Evidence(String nodeId, String nodeType, String chunkText, double score, String graphPath) {
        this(nodeId, nodeType, chunkText, score, graphPath, null, null, null);
    }

    public Evidence(String nodeId, String nodeType, String chunkText, double score, String graphPath,
                    String sourceUrl) {
        this(nodeId, nodeType, chunkText, score, graphPath, sourceUrl, null, null);
    }
}
