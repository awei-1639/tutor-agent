# 重构前结构盘点

日期：2026-09-03

本文件是结构重构的基线，不是目标架构。它记录当前代码的实际形态，用来决定后续哪些类应保留、合并、移动或提取。

## 1. 当前规模

当前后端生产代码约 235 个 Java 文件，测试代码约 117 个 Java 文件。主要模块如下：

| 当前包 | 文件数 | 约行数 | 主要问题 | 初步归属 |
|---|---:|---:|---|---|
| memory | 28 | 3375 | local/application/external/policy 分裂，跨 Conversation/LLM/Resume | conversation/memory |
| llm | 43 | 2750 | Gateway、策略、Provider、结构化 Schema 混在一起 | platform/llm |
| knowledge | 19 | 2157 | 上传、OSS、解析、入库 Worker 混在同一层 | knowledge |
| interview | 17 | 2116 | 会话、回合、报告、异步完成、评测混合 | coaching/interview |
| chat | 13 | 2096 | ChatService 是全系统编排中心，依赖约 25 个协作者 | conversation/chat |
| retrieval | 12 | 1743 | vector/fusion/graph/agentic 分开但互相穿透 | knowledge/retrieval |
| expert | 7 | 1673 | Router、专家并发、聚合和策略集中 | agent |
| context | 17 | 1339 | Prompt 区段、预算、改写与会话上下文边界不清 | conversation/context |
| config | 19 | 751 | 配置、健康检查、执行器和基础设施装配混合 | platform |
| profile | 5 | 739 | 画像持久化和技能对齐跨到 retrieval/LLM | identity/profile |
| tool | 16 | 611 | 工具协议、执行、幂等、业务工具混合 | agent/tool + platform |
| auth | 7 | 593 | 认证逻辑相对集中，但被多个 Controller 直接引用 | identity/auth |
| plan | 5 | 578 | 已完成第一阶段拆分，边界相对清晰 | coaching/plan |
| push | 5 | 434 | 岗位推送、技能缺口、计划写入混合 | coaching/career |
| resume | 3 | 349 | 简历上传、解析、Embedding 和 PII 边界混合 | identity/resume |
| admin | 2 | 286 | 管理 API 和管理数据操作集中度不足 | identity/admin |
| guard | 2 | 245 | 引用护栏属于 Agent/Knowledge 横切能力 | agent/guard |
| eval | 3 | 537 | 评测 API 和线上服务边界需要隔离 | evaluation |

## 2. 最大结构风险

按维护风险排序：

1. `ChatService`：约 822 行，连接上下文、路由、检索、专家、工具、引用、持久化和后台任务。
2. `LlmGateway`：约 872 行，连接 Provider、预算、并发、重试、流式、usage 和 telemetry。
3. `ExpertRunner`：约 609 行，包含专家选择、并发执行、超时和结构化输出。
4. `FusedRetriever`：约 505 行，混合召回、图扩展、融合、重排和降级集中。
5. `IntentRouter`：约 412 行，模型路由、解析、校准和 fallback 需要进一步分层。
6. `ConversationStore`/`EpisodeStore`：各约 340 行，SQL、加密和领域数据映射耦合。
7. `KnowledgeUploadSessionService`：约 337 行，上传会话、OSS、分片和入库状态混合。
8. `InterviewSession`/`InterviewReportService`：会话状态、模型调用、任务和计划联动范围过大。

## 3. 依赖事实

- 约 151/235 个生产 Java 文件直接使用 Spring。
- `JdbcTemplate` 出现在约 54 个生产文件中，SQL 访问并未形成稳定的 Repository 边界。
- 定时任务、虚拟线程、线程池或后台 Worker 分散在 Chat、LLM、Memory、Knowledge、Interview、Plan、Push 等模块。
- `ChatService` 直接依赖检索、上下文、专家、记忆、画像、简历、引用、工具和回合服务，是第一优先级的耦合中心。
- `Knowledge` 与 `Retrieval` 通过 `GraphScope`、`GraphExpansionPolicy`、`VectorStore` 等类型互相穿透。
- `Push/CareerGapService` 直接依赖 `ProfileService`、`SkillAlignService`、`PlanService` 和 JDBC，属于跨域写入点。
- `MemoryController` 直接组合 local memory、external sync、conversation、profile 等多个子系统。
- `ToolCatalogConfiguration` 直接装配 Profile、Push、Resume 等业务服务，工具层存在业务反向依赖。

## 4. 目标边界与处置建议

### identity

包含 auth、profile、resume、admin。对外只暴露身份、画像和简历用例；简历解析可以调用 AI Port，但不直接依赖 Provider 实现。

### conversation

包含 chat、context、memory。Conversation 是会话和回合权威；Memory 是从会话派生的长期信息。Memory 可以读取会话，但不能拥有 Chat Turn 的终态。

### coaching

包含 plan、interview、career/push。面试弱项生成计划任务时通过 coaching Application API，不直接依赖另一个 Service 的内部 DTO。

### knowledge

包含 document、ingestion、retrieval、graph、citation。检索通道是内部策略，调用方只接触 `RetrievalUseCase` 和稳定结果模型。

### agent

包含 routing、expert、tool、guard。Agent 负责决策和编排；工具执行仍通过受权限、预算、超时和幂等保护的端口。

### platform

包含 LLM Provider、JDBC/事务适配器、调度、限流、配置、监控。平台层不能反向依赖业务 Service。

## 5. 迁移顺序

```text
当前已完成：Plan 持久化/Worker/模型拆分
当前已完成：LLM 消息与流式回调公共契约隔离
        ↓
Chat 行为特征测试和 TurnState
        ↓
Chat 上下文加载与回答收口分离
        ↓
LLM Gateway 内部策略/Provider 分离
        ↓
Knowledge/Retrieval 边界收敛
        ↓
Conversation/Memory 边界收敛
        ↓
LangGraph Agentic Retrieval 影子试点
```

## 6. 明确不做的重构

- 不按文件类型建立新的 `controllers/services/repositories` 大目录；
- 不把每个方法都拆成一个类；
- 不一次性移动所有包；
- 不在结构重构期间改 API、SSE、数据库 Migration、Prompt 或模型；
- 不让 LangGraph Checkpointer 与当前数据库同时成为业务状态权威；
- 不因为类已经存在就默认保留，最终必须按调用关系和独立变化原因处置。

## 7. 下一步工作包

下一工作包是 Chat 的“行为锁定”，不是立即移动 Chat 文件：

1. 汇总当前 `ChatServiceTurnPathsTest`、预算、回合和 HTTP 测试为行为矩阵；
2. 为每条路径记录输入、阶段事件、持久化、副作用和终态；
3. 定义 `TurnState` 的最小字段，不先引入 LangGraph；
4. 将上下文加载、路由/检索、回答派发、完成收口的边界写成接口；
5. 只抽取第一个边界并运行定向测试；
6. 通过后再处理下一个边界。

该工作包完成前，不重命名全部包、不接入 LangGraph、不做数据库访问层大迁移。
