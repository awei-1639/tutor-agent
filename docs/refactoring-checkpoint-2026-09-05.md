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

## 后续切片（同日）：push 域三个 Store 的真实数据库回归

- `PushStoresPostgresIT`（4 项）：通知读写与用户隔离（unread 过滤、markRead 不可跨用户、
  guide 去重判断）、已释放岗位查询（目标匹配、无匹配回退、text[] 数组映射含 NULL）、
  岗位释放批次与 fetched_at、推送任务幂等（claim 二次为 false、失败记录阻止重推并从候选中排除）、
  最新简历 embedding 投影与 pgvector 余弦相似度（正交=0、自相似=1）。
- 钉住当前行为：`recordFailure` 之后 `claimPush` 返回 false（唯一索引 `uq_push_tasks_user_job`），
  失败岗位不再出现在候选列表——即当前实现中失败推送不会自动重试，行为变化需单独立项。
- 验证：WSL + Testcontainers 全量 Flyway 下 4 项通过；`mvn -B -ntp verify` 420 项通过、
  1 项跳过、JaCoCo 门禁通过；`git diff --check` 通过。

## 后续切片（同日）：Memory 域回归审计

盘点结论：`FactStore`（含密钥轮换，6 项 `UserFactsPostgresIT`）、`ConversationStore` 消息加密路径
（3 项 `ConversationStoreEncryptedPostgresIT`）与 `MemorySyncJobStore` 的 claim/lease/fencing
（4 项 `MemorySyncOutboxPostgresIT` 经 facade 覆盖）已有真实数据库回归；缺口是澄清状态/摘要/
watermark、Episode 全生命周期和外部记忆授权。

- 新增 `MemoryStoresPostgresIT`（3 项）：
  - 澄清状态的设置/过期自动清除/显式清除，episode watermark 的 GREATEST 不可回退，
    `messagesAfter`/`messagesToFold`/`maxFoldableMsgId` 的解密投影，加密摘要写入（明文列脱敏、
    解密列原文）与 `saveSummaryIfGeneration` 的代际 fencing（错代拒绝、对代生效）；
  - Episode 生命周期：加密写入（key id v1）、pgvector 相似度检索阈值、topics/open_items 的 PII
    脱敏契约、来源窗口幂等插入、remote_memory_id 回写与用户隔离、用户级删除、过期时间过滤，
    以及 v1→v2 密钥轮换后新旧数据均可解密、新写入使用 v2；
  - `MemoryConsentStore`：默认关闭、开启时代际 +1、重复开启不加代、关后再开 +1、手动 increment。
- 验证：WSL + Testcontainers 全量 Flyway 下 3 项通过；`mvn -B -ntp verify` 420 项通过、1 项跳过、
  JaCoCo 门禁通过；`git diff --check` 通过。
- 未发现抽取回归；topics/open_items 在加密路径上被 PII 脱敏属于既有设计，已钉入回归。

## 下一步候选

- Phase 5 收口：用 `scripts/eval-local.sh` 跑检索通道消融实验（vector/sparse/graph/rerank 组合），
  以质量、延迟、Token 成本决定 Neo4j/Rerank/多跳的存留；
- Memory/Retrieval 等剩余边界的物理包迁移（identity/conversation/coaching/... 逻辑域）仍按计划
  "先依赖规则、后逐片移动"的原则暂缓，等消融实验结论后再评估。
