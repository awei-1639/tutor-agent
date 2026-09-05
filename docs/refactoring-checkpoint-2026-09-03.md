# 重构检查点：Interview Completion

## 本次切片

将面试结束后的闭环流程从 `InterviewReportService` 中拆出：

- `InterviewReportService`：只负责报告组装、复测对比、完成任务状态 facade。
- `InterviewCompletionJobStore`：负责完成任务 SQL、session 查询、lease/fencing 和 evidence 持久化。
- `InterviewCompletionWorker`：负责定时调度、并发限制、证据生成和学习计划副作用。
- `InterviewSessionRepository`：补充评分问题查询和可选 session 查询，报告服务不再直接执行报告 SQL。

## 保持不变

- HTTP 路径、响应字段、SSE 和认证行为；
- 面试完成任务的队列状态、重试次数、10 分钟 lease 和 fencing 条件；
- evidence upsert 及学习计划创建顺序；
- Flyway migration、数据库表结构和 LLM purpose。

## 验证

- `mvn -B -ntp -DskipTests compile`：通过；
- 面试定向测试：14 项通过；
- `mvn -B -ntp verify`：405 项通过、1 项跳过，JaCoCo 门禁通过；
- WSL + Testcontainers 面试集成测试：2 项通过；
- `git diff --check`：通过。
- Memory Sync Outbox PostgreSQL 集成测试：4 项通过；
- User Facts PostgreSQL 集成测试：6 项通过。

## 下一步

本检查点之后，`KnowledgeDocumentAdminService`、`MemorySyncOutbox` 和 `FactStore` 的 SQL 边界均已收敛；下一步再评估剩余跨域调用，仍然坚持一次只拆一个稳定职责边界；不在此阶段引入 Python/LangGraph 或进行全仓库物理迁移。
