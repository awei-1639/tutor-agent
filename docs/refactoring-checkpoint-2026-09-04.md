# 重构检查点：Knowledge Admin、Memory Sync 与 Semantic Facts

## 本次切片

- `KnowledgeDocumentAdminStore`：承接文档重复检测、上传入库事务、查询投影、重试、软删除、代际查询和审计 SQL。
- `MemorySyncJobStore`：承接外部记忆 outbox 的任务认领、lease/fencing、失败重试、代际校验和状态查询。
- `FactPersistenceStore`：承接事实 SQL、pgcrypto 读写、密钥轮换和 ResultSet 映射。
- `FactPolicy`：承接事实类别归一化和幂等 hash；`FactStore` 保留稳定公共 facade。
- `InterviewTurnJobStore`：承接回答任务 SQL、认领、lease/fencing、完成/失败和用户重试状态；
- `InterviewTurnWorker`：承接定时调度、并发限制和评分任务执行；`InterviewTurnService` 保留提交/查询/重试用例。
- `InternalMemorySeedService`：承接内部评测 memory seed 的清理、embedding、Episode/Fact 播种和回写；`InternalController` 只负责请求适配。
- `NotificationStore`：承接通知查询、已读标记、引导去重和通知写入；`NotificationController` 只负责 HTTP 适配。
- `ChatTurnJobStore`：承接 durable chat turn 的 admission、幂等查询、lease/fencing、终态和恢复 SQL；
- `ChatTurnWorker`：承接回合调度、并发限制、取消、租约续期和执行；`ChatTurnService` 保留应用 API。
- `HealthReadinessService`：承接 PostgreSQL/Neo4j readiness 检查；`HealthController` 只负责 HTTP 响应。
- `PushJobStore`：承接岗位发布、候选岗位/简历向量查询、推送任务幂等和失败记录；`PushService` 保留推送编排。
- `MemoryMergePolicy`：承接本地/远端记忆候选清洗、近重复消解和确定性排序；`LongTermMemoryService` 保留远端授权和降级编排。
- `ToolExecutionPolicy`：承接工具权限、输入和副作用校验；`ToolInvocationRunner`：承接超时执行和线程池生命周期；`ToolExecutor` 保留统一工具 facade。
- `ResumeStore`：承接简历加密持久化、PII 映射和最新结构化投影查询；`ResumeService` 保留简历处理管线。
- `MessageFeedbackStore`：承接反馈写入、汇总和归因查询；`MessageFeedbackService` 保留反馈应用 facade。
- `AuthStore`：承接用户凭据和 refresh token SQL；`RefreshTokenCleanup` 承接定时清理；`AuthService` 保留认证用例。
- `AdminStore`：承接管理员概览、用户管理、审计和权限查询；`AdminService` 保留管理用例编排。
- `MessageFeedbackStore`：承接反馈写入、汇总和归因查询；`MessageFeedbackService` 保留反馈应用 facade。
- `AdminStore`：承接管理员概览、用户管理、审计和权限查询；`AdminService` 保留管理员用例编排。

## 不变约束

- HTTP、SSE、认证、用户隔离和响应字段不变；
- Flyway migration 和数据库表结构不变；
- OSS multipart 补偿、outbox lease、generation fencing、幂等和重试语义不变；
- LLM purpose、预算、超时、fallback 和 usage 行为不变；
- 不引入 Python/LangGraph，不进行全仓库物理包迁移。

## 验证

- `mvn -B -ntp -DskipTests compile`：通过；
- Knowledge/Interview/Memory 相关定向测试通过；
- `mvn -B -ntp verify`：411 项通过、1 项跳过，JaCoCo 门禁通过；
- `MemorySyncOutboxPostgresIT`：4 项通过；
- `UserFactsPostgresIT`：6 项通过；
- `InterviewSessionPostgresIT`：2 项通过；
- Interview Turn 定向测试：3 项通过；
- Chat Turn 定向测试：1 项通过；
- `ChatTurnPostgresIT`：2 项通过；
- Architecture 边界规则：显式执行 1 组规则测试并通过；不再依赖未实际执行的 ArchUnit 注解发现机制。
- `git diff --check`：通过。
- `ResumeStoreTest`：2 项通过；`AuthServicePostgresIT`：4 项通过；最新 `mvn verify`：418 项通过、1 项跳过，JaCoCo 门禁通过。

## 后续原则

Admin 本轮已补充 `AdminServiceTest` 3 项通过。

当前不再按文件数量继续拆分。下一步只处理能够明显降低跨域理解成本的边界，并优先补充行为/集成证据；若新增抽象只有单一调用方且没有独立测试价值，则合并或停止。
