# 重构检查点：Admin/Feedback/Resume 回归审计与真实数据库回归

## 本次切片

按主计划"下一切片"指示完成 Admin/Feedback/Resume 边界的回归审计，补齐真实数据库路径回归测试：

- `AdminStorePostgresIT`（2 项）：概览统计、用户生命周期（disable 的 COALESCE 语义、restore、softDelete）、
  搜索/状态过滤/分页、审计写入与回读（含无 target 用户的行为行）；
- `MessageFeedbackStorePostgresIT`（2 项）：反馈写入的用户隔离与 upsert、非 assistant 消息拒绝、
  totals/reasons/latestNotHelpful 聚合、负反馈归因 SQL 与 turn_traces 的 router/retrieve snapshot 关联
  （含无 trace 时的全默认值路径）；
- `ResumeStorePostgresIT`（1 项）：pgcrypto 加密原文与 PII 映射的写读往返、1024 维向量入库、
  最新 structured 投影与用户隔离。

## 发现并修复的回归

- `AdminStore.audit` 行映射误用 `Map.of`（不接受 null 值），导致 `target_user_id` 为空的审计行
  （如 view_overview 类操作）读取时抛 NPE。迁移前 `AdminService` 使用 `LinkedHashMap`；
  已恢复 null 容忍映射并新增 `AdminStorePostgresIT` 回归覆盖。

## 不变约束

- HTTP、SSE、认证、用户隔离和响应字段不变；
- Flyway migration 和数据库表结构不变；
- LLM purpose、预算、超时、fallback 和 usage 行为不变；
- 不引入 Python/LangGraph，不进行全仓库物理包迁移。

## 验证

- `mvn -B -ntp -DskipTests compile` / `test-compile`：通过；
- WSL + Testcontainers（pgvector/pg16，全量 67 个 Flyway migration）：
  `AdminStorePostgresIT` 2 项、`MessageFeedbackStorePostgresIT` 2 项、`ResumeStorePostgresIT` 1 项，全部通过；
- `mvn -B -ntp verify`：420 项通过、1 项跳过，JaCoCo 门禁通过；
- `git diff --check`：通过。

## 下一步候选

- push 域三个 Store（`NotificationStore`、`CareerJobStore`、`PushJobStore`）尚无真实数据库回归，为下一候选切片；
- 其余已收敛边界维持"不为拆而拆"原则：只有能明显降低跨域理解成本时才继续新增类型，优先补充行为/集成证据。
