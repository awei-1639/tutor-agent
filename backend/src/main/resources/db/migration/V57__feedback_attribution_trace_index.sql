-- 负反馈归因只读取路由与检索 span；复合索引避免按 trace 回查时扫描整张追踪表。
CREATE INDEX IF NOT EXISTS idx_turn_traces_feedback_attribution
    ON turn_traces (trace_id, node, id DESC)
    WHERE node IN ('router', 'retrieve');
