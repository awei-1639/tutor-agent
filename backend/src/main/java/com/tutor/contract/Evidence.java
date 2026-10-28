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
        String contentHash,   // 入库时对当前证据文本计算的 SHA-256, 可为 null
        String sourceKind     // self = 用户自有材料 / reference = 公开参考资料
) {
    public static final String KIND_SELF = "self";
    public static final String KIND_REFERENCE = "reference";

    public Evidence {
        if (sourceKind == null || sourceKind.isBlank()) sourceKind = KIND_REFERENCE;
    }

    /** 兼容构造: 未指明来源时按公开参考资料处理 (测试夹具与历史调用点用)。 */
    public Evidence(String nodeId, String nodeType, String chunkText, double score, String graphPath,
                    String sourceUrl, String sourceStatus, String contentHash) {
        this(nodeId, nodeType, chunkText, score, graphPath, sourceUrl, sourceStatus, contentHash,
                KIND_REFERENCE);
    }

    public boolean selfMaterial() {
        return KIND_SELF.equalsIgnoreCase(sourceKind);
    }
}
