# 项目结构重构主计划

> 状态：执行中（已完成 Phase 0–6 的多个可验证切片）
> 日期：2026-09-03
> 目标：降低维护成本，不改变已存在的用户行为、安全边界和数据语义。

## 1. 为什么要重构

当前项目的问题不是单纯的文件数量多，而是职责、依赖和变化原因没有稳定地对齐：

- 业务域、平台能力、横切能力和评测能力平铺在同一层；
- `ChatService`、`LlmGateway` 等核心类承担过多职责；
- SQL、业务规则、异步 Worker 和模型调用在部分 Service 中混杂；
- 部分公共接口直接暴露第三方 SDK 类型；
- 一个用户用例需要跨多个顶层包追踪；
- 继续“一个问题新增一个类”会同时造成 God Class 和类爆炸。

重构的成功标准不是把 235 个文件减少到某个数字，而是让一个需求可以沿着稳定路径被定位、修改和验证。

## 2. 不变的外部契约

整个重构期间，除非单独立项，不改变以下内容：

- HTTP 路径、请求字段、响应字段和状态码；
- SSE 事件名称、顺序、序号和取消语义；
- JWT、Cookie、CSRF、用户/租户隔离和管理员权限；
- PostgreSQL 表结构、Flyway 历史 Migration 和已有数据含义；
- Outbox、任务租约、fencing、幂等和重试语义；
- LLM purpose、预算、超时、重试、降级和 usage 结算规则；
- 前端 API 调用方式；
- 既有评测集、指标定义和质量阈值。

任何需要改变上述内容的工作，必须另开变更说明和迁移方案，不得混入结构重构。

## 3. 目标架构

### 3.1 逻辑业务域

```text
com.tutor
├── identity       auth / profile / resume / admin
├── conversation   chat / context / memory
├── coaching       plan / interview / push
├── knowledge      document / ingestion / retrieval / graph / citation
├── agent          routing / expert / tool / guard
├── evaluation     evaluation endpoints and quality models
└── platform       llm / persistence / scheduling / ratelimit / security / config / observability
```

这是目标边界，不要求一次性移动全部物理文件。迁移必须先有依赖规则，再逐个切片移动。

### 3.2 单个用例的依赖方向

```text
HTTP/API
  ↓
Application Use Case
  ↓
Domain Policy / Domain Model
  ↓
Port
  ↓
Infrastructure Adapter
```

约束：

- Controller 不直接访问 JDBC、Provider Client 或第三方 SDK；
- Application Service 不包含 SQL；
- Domain Policy 不依赖 Spring、JDBC、LangChain4j 或 Servlet；
- Platform 不依赖具体业务域；
- Agent 不直接写核心业务表；
- 跨域调用使用明确的 Application API，不通过共享内部字段或万能 `contract` 绕行。

## 4. 类的处置规则

每个生产类进入处置表，只允许以下动作：

| 动作 | 含义 |
|---|---|
| KEEP | 职责清晰，位置合理，保留 |
| MOVE | 只调整包归属，不改行为 |
| MERGE | 与同一变化原因的类合并 |
| EXTRACT | 从 God Class 提取稳定职责 |
| DELETE | 无独立价值的包装或重复实现 |
| RENAME | 名称不能表达真实职责时改名 |

不以“每个方法一个类”为目标。只有满足至少两项时才新增类型：

- 有独立变化原因；
- 有独立生命周期；
- 有独立测试价值；
- 有清晰输入输出契约；
- 能减少调用方的认知负担。

## 5. 阶段计划

### Phase 0：建立检查点

**目标**：让当前状态可回滚。

**动作**：

1. 保存当前已完成的 Plan 重构和 LLM 契约隔离；
2. 明确工作区中原先存在的 `evals/run_citation_eval.mjs`、`docs/diagrams/` 和 HTML 文件，不覆盖、不回退；
3. 记录当前 HEAD、测试结果、数据库 Migration 版本和文件清单；
4. 将后续阶段与当前变更分开。

**通过条件**：

- `mvn verify` 通过；
- 前端 lint、typecheck、build 通过；
- `git diff --check` 通过；
- 没有修改 Flyway Migration 和前端契约。

### Phase 1：结构盘点

**目标**：不凭感觉移动文件，建立完整地图。

为每个生产类记录：

```text
class
domain
role: controller / use-case / policy / port / adapter / store / worker / config
spring dependency
jdbc dependency
llm sdk dependency
cross-domain callers
test coverage
candidate action
```

另外单独盘点：

- Controller → Application → Store 调用链；
- LLM 调用点及 purpose；
- 每个线程池、定时器、Worker 和 lease 表；
- 每个 SQL 文件/类的表所有权；
- 直接跨模块依赖；
- 重复的错误映射、重试、超时和 fallback；
- `contract` 中真正跨域的类型。

**交付物**：

- 模块依赖图；
- 用例路径图；
- 类处置表；
- SQL 所有权表；
- 线程池和后台任务表。

### Phase 2：先立规则，再移动代码

增加并逐步收紧 ArchUnit 规则：

```text
Controller 不依赖 JdbcTemplate
Controller 不依赖 Provider Client
Application 不包含 SQL 字符串
Domain 不依赖 Spring/JDBC/LangChain4j
Gateway 接口不依赖第三方 SDK
Platform 不依赖业务域
Retrieval 不依赖 Chat API
Memory 不依赖 Chat API
```

新规则先作用于新边界，不要求一次性修完全部历史代码。

### Phase 3：Chat 主流程重构

这是最高收益阶段，因为 Chat 是多个模块的耦合中心。

先增加行为特征测试，覆盖：

- 直答；
- Router 成功、失败和非法输出；
- 单跳、混合和多跳检索；
- 专家全成功、部分成功和全失败；
- Tool Loop 成功和回退；
- Citation Guard 成功和降级；
- Token Budget 不足；
- Provider 超时；
- SSE 取消和客户端断开；
- durable turn 的完成、失败和 fencing；
- 后置画像和记忆任务。

然后引入显式回合状态：

```text
TurnInput
→ ContextSnapshot
→ RoutingDecision
→ RetrievalResult
→ AnswerPlan
→ AnswerResult
→ CompletionResult
```

`ChatService` 只协调流程，不再直接承载所有策略和实现细节。每次只抽取一个阶段，并在该阶段测试通过后继续。

### Phase 4：LLM 平台层收敛

当前已完成消息和流式回调的公共契约隔离，并完成请求策略首个切片。后续只整理内部职责：

```text
Model Gateway
├── message contract
├── budget policy
├── timeout/retry policy
├── concurrency policy
├── provider adapter
├── usage recorder
└── telemetry
```

不在本阶段改变模型、Prompt、预算阈值、Provider 或重试次数。

### Phase 5：Knowledge/Retrieval 收敛

将文档、分块、Embedding、稀疏检索、向量检索、图扩展、Rerank 和引用校验纳入一个知识域，但保留独立策略接口。

在结构整理之后做消融实验：

```text
vector_only
sparse_only
vector_sparse
vector_graph
vector_sparse_graph
vector_sparse_rerank
agentic
```

以质量、延迟、Token 成本和运维复杂度决定是否保留 Neo4j、Rerank 和多跳，而不是因为已经实现就默认保留。

### Phase 6：Conversation/Memory 收敛

明确边界：

```text
Conversation = 会话、消息、回合和摘要的业务事实
Memory       = 从会话派生的 Episode、Fact 和远端同步状态
```

Memory 可以读取 Conversation，但不能反向控制 Conversation 的核心终态。记忆删除、代际 fencing、Outbox 和 Mem0 降级必须保持原语义。

### Phase 7：LangGraph 影子试点

只有前述结构和质量基线稳定后才进入。

第一批只迁移：

```text
Agentic Retrieval
Expert Dispatch
Aggregation
```

不迁移：

```text
Auth / CSRF
SSE
ChatTurn persistence
Token Budget
Tool Idempotency
Outbox
Core business transactions
```

使用独立 Python 服务或实验目录，先影子运行：

```text
Java 结果 → 返回用户
Python LangGraph 结果 → 仅记录比较
```

达到以下条件才允许小流量切换：

- 质量不低于 Java 基线；
- P95 不超过 Java 的 1.2 倍；
- 平均 Token 成本增加不超过 10%；
- 取消、超时、错误和降级测试全部通过；
- Java 路径可以一键回退；
- LangGraph 没有形成第二套业务状态权威。

## 6. 测试矩阵

每个重构切片必须执行：

### 静态与编译

```text
mvn -DskipTests compile
mvn -DskipTests test-compile
git diff --check
```

### 定向测试

覆盖被修改模块、直接调用方和相关架构规则。

### 全量测试

```text
mvn verify
```

### 集成测试

涉及数据库、Flyway、lease、用户隔离、认证或真实 HTTP 时，使用 WSL + Docker 执行相关 `*IT`。

### 前端验证

只有接口或响应模型发生影响时才执行前端 E2E；每个阶段至少执行：

```text
npm run lint
npm run lint:types
npm run build
```

### AI 评测

涉及 Prompt、路由、检索、模型、引用或 Agent 行为时，必须记录：

```text
git_sha
dataset_version/hash
knowledge_snapshot
model/provider
prompt_version
run_kind
quality_failures
infra_failures
```

基础设施失败不得计为检索质量的 0 分。

## 7. 提交和回滚策略

一个提交只允许包含：

```text
一个结构切片
相关测试
必要的架构门禁
必要的文档
```

禁止将以下内容混在一起：

- 重构 + 新功能；
- 重构 + Prompt 优化；
- 重构 + 数据库 Migration；
- 重构 + Provider 更换；
- 重构 + LangGraph 接入；
- 后端结构调整 + 前端大改。

每个阶段结束后保留一个可运行检查点。出现以下任一情况时停止并回滚该切片：

- 原有测试失败且不能证明是测试问题；
- API/SSE/权限行为发生变化；
- lease、幂等或取消语义无法证明等价；
- 全量测试波动性增加；
- 需要通过新增 fallback 掩盖行为差异；
- 代码定位成本没有下降。

## 8. 暂停和停止条件

### 暂停新增功能

在以下事项完成前，不新增 Agent、Memory、Provider 或页面：

- Chat 主流程可读；
- LLM 公共契约稳定；
- 评测可以区分质量失败和基础设施失败；
- 核心用户闭环可验证。

### 停止某个重构方向

如果一个阶段完成后：

- 类数量增加但耦合没有下降；
- 跨模块调用没有减少；
- 测试范围反而扩大且无法定位；
- 新抽象只被一个调用方使用；

则停止继续抽象，合并类型或回退设计。

### 停止 LangGraph 迁移

如果影子运行无法在质量、成本、延迟或可维护性上证明收益，则保留 Java 实现，不为了框架统一而迁移。

## 9. 当前执行顺序

```text
1. 保存当前重构检查点
2. 完成全仓库结构和依赖盘点
3. 增加分阶段 ArchUnit 门禁
4. 为 Chat 主流程补行为特征测试
5. 重构 ChatService 为显式 Pipeline/TurnState
6. 整理 LLM 内部职责
7. 收敛 Knowledge/Retrieval
8. 收敛 Conversation/Memory
9. 再评估 LangGraph 影子试点
```

当前不执行 Python 全量迁移，也不执行 LangGraph 生产接入。

## 10. 已完成进度（2026-09-03）

- Plan 模块已完成持久化、Worker 和模型边界拆分；
- LLM 结构化和流式公共契约已改用项目自有消息/事件类型；
- Chat 已将事件协议和 `MemoryRef` 从 `ChatService` 移出；
- Chat 已引入内部 `TurnState`，统一原始问题、执行问题、路由和检索结果；
- Chat 已将上下文加载、配额前置检查、会话历史处理、记忆代际和画像快照移至 `ChatContextLoader`；
- Chat 已将引用映射、assistant 持久化、done 事件、引用校验和后置任务移至 `ChatCompletionFinalizer`；
- Chat 已将查询改写、Router 调用、预算降级和路由 trace 移至 `ChatRoutingStage`；
- Chat 已将记忆/事实召回、Agentic 检索、图谱策略、citation 事件和 retrieval trace 移至 `ChatRetrievalStage`；
- Chat 已移除未使用的 `FusedRetriever`、`SummaryFolder` 和 `EpisodeSummarizer` 注入，减少无效协作者；
- Chat 已将直答、Tool Loop、专家扇出、聚合和 Prompt 组装移至 `ChatAnswerStage`，主服务只保留回合流程协调；
- LLM 已将消息裁剪、token 估算、rerank 估算、超时、重试和预算压力输出上限集中到 `LlmRequestPolicy`；自适应预留校准也归入该策略，避免 `LlmGateway` 同时维护静态规则和动态策略状态；
- LLM 已将流式 HTTP/SSE、底层请求取消、流式 usage 结算和 Provider 响应解析移至 `LlmStreamingExecutor`；`LlmGateway` 只负责预算预留、并发闸门和公共接口编排；
- LLM 已将结构化 JSON Provider 构造、显式重试、fallback 和 usage 结算移至 `LlmJsonExecutor`；SDK 重试仍保持关闭，原有 purpose、超时和预算边界不变；
- LLM 流式取消在关闭响应体之外，同时取消底层 `HttpClient` 请求 Future，避免客户端断开后 Provider 连接残留；对应取消、usage 结算和并发释放回归测试通过；
- Retrieval 已将 dense/sparse/graph 候选池构建、图扩展起点选择、稀疏降级和候选池 telemetry 移至 `RetrievalCandidatePipeline`；`FusedRetriever` 保留对外检索接口、可选 rerank 及融合策略兼容入口；
- Agentic Retrieval 已将 judge JSON 的不可信输出解析移至 `RetrievalJudgeOutputParser`，多跳循环仅消费明确的判定结果；既有 `AgenticRetriever.parse` 兼容入口保留；
- Agentic Retrieval 已将多跳循环、hop 衰减、前沿扩展、judge 停止条件和结果累积移至 `AgenticRetrievalLoop`；`AgenticRetriever` 只保留公共 API、版本查询和兼容入口；
- Expert 已将带 token 上限和 citation 映射的共享 Prompt 简报构建移至 `ExpertBriefingBuilder`；专家并发、超时和结构化输出行为保持不变；
- Expert 已将专家任务提交、批次 deadline、取消、超时和 SSE 阶段通知移至 `ExpertTaskExecutor`；`ExpertRunner` 保留专家契约校验和结构化结果转换；
- Expert 已将单个专家的 structured output 调用、结果序列化、内容数组校验和 citation 校验移至 `ExpertOutputProcessor`；`ExpertRunner` 不再直接持有 JSON/Provider 处理细节；
- Routing 已将 Router JSON 的字段解析、一致性校验、意图/检索 facet 归一化移至 `IntentDecisionParser`；`IntentRouter` 仅负责 LLM 调用、降级和置信度校准；
- Career Gap 已将岗位查询 SQL 与 ResultSet 映射移至 `CareerJobStore`；`CareerGapService` 只负责画像对齐、缺口计算和计划任务编排，并增加 JDBC 边界门禁；
- Profile 已将画像快照、事件账本、保存、代际 fencing、删除和衰减所需 SQL 移至 `ProfileStore`；`ProfileService` 只负责画像抽取、合并、审计编排和调度入口，并增加 JDBC 边界门禁；
- Profile 已将抽取门控、PII 脱敏、structured output 转换和增量清洗移至 `ProfileMessageExtractor`；画像合并与持久化事务仍由 `ProfileService` 编排；
- Conversation/Memory 已将会话消息持久化与摘要、澄清状态、Episode watermark 持久化分别移至 `ConversationMessageStore`、`ConversationStateStore`；`ConversationStore` 保留现有公共 facade 和数据契约；
- Conversation 加密 PostgreSQL 集成回归已在 WSL/Testcontainers 中通过（3 项），验证加密/明文 citations 与 durable turn 双消息幂等约束未受拆分影响；
- Episode 已将查询、解密感知投影、开放事项和行映射移至 `EpisodeSearchStore`；`EpisodeStore` 保留写入、删除、远端 ID 回写和公共数据契约；
- Architecture 已补充 LLM Port 不得依赖 Provider SDK、Memory 不得依赖 Chat API 的边界门禁，防止后续重构重新形成反向依赖；
- Knowledge Upload 已将上传会话创建、续期、完成、过期清理和审计 SQL 移至 `KnowledgeUploadSessionStore`；OSS multipart 行为与入库任务事务保持不变；
- Knowledge Ingestion 已将文档读取、杀毒、解析、分块、Embedding staging、租约检查和发布流程移至 `KnowledgeIngestionService`；`KnowledgeDocumentService` 保留对外文档 facade；
- Interview 已将 blueprint/session 写入、transcript、history、feedback、岗位技能读取和历史题目读取收敛到现有 `InterviewSessionRepository`；`InterviewSession` 保留状态机、LLM 编排和事务用例；
- Interview Completion 已将完成任务 SQL、lease/fencing 和 evidence 写入收敛到 `InterviewCompletionJobStore`，将定时调度、并发限制、重试和学习计划副作用收敛到 `InterviewCompletionWorker`；`InterviewReportService` 仅保留报告与复测对比组装；
- Knowledge Ingestion 已将解析、分块、Embedding staging、租约检查和发布流程收敛到 `KnowledgeIngestionService`；`KnowledgeDocumentService` 仅保留文档 facade；
- Knowledge Admin 已将文档 SQL、事务删除、重复检测、审计和 ResultSet 映射收敛到 `KnowledgeDocumentAdminStore`；`KnowledgeDocumentAdminService` 仅保留管理员校验、OSS 补偿和业务编排；
- Memory Sync 已将 outbox 任务认领、lease/fencing、重试、代际校验和状态查询收敛到 `MemorySyncJobStore`；`MemorySyncOutbox` 仅保留外部记忆意图入队和兼容 facade，`MemorySyncWorker` 的执行行为不变；
- Semantic Memory 已将事实 SQL、加密读写和 ResultSet 映射收敛到 `FactPersistenceStore`，将类别归一化和幂等哈希规则收敛到纯 `FactPolicy`；`FactStore` 仅保留稳定公共 facade，事实 API、代际 fencing 和删除语义不变；
- Interview Turn 已将回答任务 SQL、认领、lease/fencing、完成/失败状态和重试持久化收敛到 `InterviewTurnJobStore`，将定时调度、并发限制和评分提交收敛到 `InterviewTurnWorker`；`InterviewTurnService` 仅保留提交、查询、用户重试和预算归属用例；
- Internal Evaluation 已将 memory seed 的 JDBC 清理、真实 embedding、Episode/Fact 播种和年龄回写收敛到 `InternalMemorySeedService`；`InternalController` 不再直接依赖 JDBC 或 EmbeddingGateway，保留原有内部评测请求/响应行为；
- Notification 已将通知查询、已读标记、引导去重和通知写入收敛到 `NotificationStore`；`NotificationController` 仅负责认证上下文和 HTTP 适配，`PushService` 通过该边界写入通知；
- Durable Chat Turn 已将回合 admission、幂等查询、lease/fencing、终态写入和恢复所需 SQL 收敛到 `ChatTurnJobStore`，将调度、并发限制、取消、租约续期和执行收敛到 `ChatTurnWorker`；`ChatTurnService` 保留回合应用 API 和消息事务编排；
- Health Probe 已将 PostgreSQL/Neo4j readiness 检查和安全日志收敛到 `HealthReadinessService`；`HealthController` 仅负责 healthz/readyz HTTP 响应，并通过全局 Controller-JDBC 架构规则约束；
- Push 已将岗位发布、候选查询、简历向量查询、推送任务幂等和失败记录收敛到 `PushJobStore`；`PushService` 保留画像对齐、匹配评分、推送编排和定时锁逻辑；
- Memory Recall 已将候选清洗、近重复消解、相关度/新鲜度排序和召回上限收敛到纯 `MemoryMergePolicy`；`LongTermMemoryService` 保留本地/远端召回、授权、降级和删除编排；
- Tool Execution 已将 agent/输入/副作用校验收敛到 `ToolExecutionPolicy`，将超时执行和线程池生命周期收敛到 `ToolInvocationRunner`；`ToolExecutor` 保留工具注册、幂等、审计和统一错误映射；
- Resume 已将简历加密写入、PII 映射保存和最新结构化投影查询收敛到 `ResumeStore`；`ResumeService` 保留解析、脱敏、LLM/Embedding 编排和画像回填；
- Auth 已将用户凭据、注册写入、refresh token 轮换和清理 SQL 收敛到 `AuthStore`，将定时清理入口移至 `RefreshTokenCleanup`；`AuthService` 保留注册、登录、JWT/refresh token 签发与轮换用例；
- Admin 已将管理员概览、用户管理、审计和权限查询收敛到 `AdminStore`；`AdminService` 仅保留管理员权限校验与管理用例编排；
- Admin 已将管理员概览、用户列表/状态操作、审计和管理员权限查询收敛到 AdminStore；AdminService 仅保留权限校验和管理用例编排；
- Message Feedback 已将反馈写入、汇总、坏例归因查询和 ResultSet 映射收敛到 MessageFeedbackStore；MessageFeedbackService 仅保留反馈应用 facade 和汇总编排；
- Architecture Boundary Test 已改为显式 JUnit 执行全部 ArchUnit 规则，避免此前测试报告“0 tests”导致门禁未实际运行；当前边界规则实际执行并通过；
- 当前检查点已通过 418 项测试（跳过 1 项）、实际执行的架构边界规则、JaCoCo 门禁和 `git diff --check`；Interview Testcontainers 集成测试 2 项、Memory Sync Outbox 4 项、User Facts 6 项和 Chat Turn 2 项 PostgreSQL 集成测试通过；未修改 HTTP、SSE、数据库 Schema、前端或 LangGraph 接入。
- Admin/Feedback/Resume 回归审计已完成：新增 `AdminStorePostgresIT`（2 项）、`MessageFeedbackStorePostgresIT`（2 项）、`ResumeStorePostgresIT`（1 项）真实数据库回归（WSL + Testcontainers，全量 Flyway migration）；
- 回归审计发现并修复一处抽取回归：`AdminStore.audit` 行映射误用 `Map.of`，无 target 用户的审计行读取时抛 NPE；已恢复迁移前 `LinkedHashMap` 的 null 容忍语义；
- 当前检查点已通过 420 项测试（跳过 1 项）、JaCoCo 门禁和 `git diff --check`；
- push 域回归审计已完成：新增 `PushStoresPostgresIT`（4 项）覆盖通知读写与用户隔离、岗位查询回退、推送任务幂等和 embedding 相似度；`recordFailure` 后 `claimPush` 为 false、失败岗位不再重推的当前语义已钉入回归；
- Memory 域回归审计已完成：新增 `MemoryStoresPostgresIT`（3 项）覆盖澄清状态/摘要加密与代际 fencing、Episode 全生命周期（加密、轮换、PII 脱敏、来源窗口幂等、过期过滤、用户隔离）和外部记忆授权代际语义；未发现抽取回归；
- Phase 5 消融实验已完成（docs/phase5-retrieval-ablation-2026-09-05.md）：稀疏通道与 Rerank 保留（+3.4pt / +2.4pt Recall@5，零延迟成本），Agentic 多跳保留为路由按需升级、不做默认（multi_hop +4.7pt 但延迟 4 倍）；图谱扩展证据间接，需 vector_sparse 模式单独归因；
- 消融准备期间发现并修复两处回归：final @Repository 导致应用无法启动（新增 ArchUnit 门禁 repositoryBeansAreNotFinal），/error ERROR dispatch 被拦截器改写导致所有 5xx 变 401（影响所有业务端点的错误可见性，属既有问题）；
- 当前检查点已通过 421 项测试（跳过 1 项）、JaCoCo 门禁和 `git diff --check`。

下一切片只在新的检查点上进行：job_requirement 切片数据质量 badcase、Badcase 08 累积状态根因定位，或按主计划评估 Phase 6 收口/Phase 7 前置条件；物理包迁移在上述质量基线稳定后再评估。
