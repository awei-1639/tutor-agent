package com.tutor.retrieval;

/**
 * 检索范围会一路传递至 SQL/Neo4j。公开种子数据保持可读；用户拥有的记录
 * 仅所有者可读。tenantId 为启用租户成员关系的部署预留。
 */
public record GraphScope(long userId, String tenantId, boolean includePublic) {
    public GraphScope {
        if (userId < 0L) throw new IllegalArgumentException("userId must be non-negative");
        tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    public static GraphScope forUser(long userId) {
        return new GraphScope(userId, null, true);
    }

    public static GraphScope forUser(long userId, String tenantId) {
        return new GraphScope(userId, tenantId, true);
    }

    public static GraphScope publicOnly() {
        return new GraphScope(0L, null, true);
    }

    public boolean isPublicOnly() {
        return userId == 0L && tenantId == null;
    }
}
