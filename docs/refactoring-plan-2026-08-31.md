# 结构重构实施计划（2026-08-31）

## 1. 目标

在不改变公开 HTTP/SSE 契约、数据库 Schema、鉴权边界和现有业务行为的前提下，降低巨型 Service、SQL 散落、后台任务与业务编排混杂带来的维护风险。

本轮不追求一次性重排整个仓库，而采用“小切片、可验证、可回滚”的渐进方式。每个阶段必须独立编译、测试通过，才能进入下一阶段。

## 2. 不变量

以下内容在重构期间保持不变：

- API 路径、请求字段、响应字段和 HTTP 状态码；
- SSE 事件名称、顺序约定和取消语义；
- Flyway 历史 Migration 与现有表结构；
- 用户/租户作用域、CSRF、Cookie、JWT 和管理员权限；
- LLM purpose、Token 预算、超时、重试和降级语义；
- 任务认领的 `FOR UPDATE SKIP LOCKED`、lease token 和 fencing 条件；
- 前端调用协议；
- 已存在的评测集和阈值。

## 3. 工作区保护

开始重构时工作区已有以下非本轮变更，本轮不得覆盖或清理：

- `evals/run_citation_eval.mjs` 的未提交修改；
- `docs/diagrams/` 未跟踪文件；
- `docs/project-implementation-details.html` 未跟踪文件。

所有差异检查必须排除“为了工作区干净而回退用户文件”的做法。

## 4. 目标依赖方向

```text
API Controller
    ↓
Application Service / Use Case
    ↓
Domain Policy + Port
    ↓
Infrastructure Adapter / Store
```

约束：

- Controller 不直接调用 JDBC、LLM 或第三方客户端；
- Application Service 不包含 SQL；
- 纯业务规则不依赖 Spring、JDBC 或模型 SDK；
- 公共 Agent 契约逐步移除 LangChain4j 类型；
- 后台任务的 claim/lease/fencing 与任务执行分开；
- 不为每个条件分支创建一个类，只有独立变化原因才拆分。

## 5. 分阶段路线

### Phase 1：学习计划模块（本轮执行）

现状：`PlanService` 同时负责 API 用例、LLM 生成、SQL、队列认领、lease、线程池、定时调度和 DTO。

调整：

- `PlanService`：保留应用用例、LLM 生成和确定性业务规则；
- `PlanStore`：集中计划、任务、打卡及生成队列的 SQL；
- `PlanGenerationWorker`：负责定时认领、并发槽、执行器和任务终态；
- `PlanModels`：承载稳定的计划模块返回契约，避免外部模块依赖 Service 的嵌套类型。

本阶段不改数据库、不改接口、不改任务 lease SQL。

### Phase 2：LLM 契约

- 建立项目自有的 message/request/event/result/usage 类型；
- 让 `JsonGenerationGateway`、`StreamingGenerationGateway` 不再暴露 LangChain4j 类型；
- 保持现有 Provider 和 `LlmGateway` 行为，先做契约隔离，再拆实现。

### Phase 3：聊天编排

- 引入显式 `TurnState`；
- 将 `ChatService` 收敛为可读 Pipeline；
- 保持现有路由、检索、专家、工具、护栏和 SSE 行为；
- 每抽取一个阶段，先增加回归测试。

### Phase 4：数据访问边界

按风险从低到高逐步抽离：

1. profile / push；
2. conversation / episode；
3. interview；
4. knowledge ingestion；
5. auth 和安全数据。

### Phase 5：LangGraph 影子试点

仅在当前 Java 基线稳定后，将 Agentic Retrieval 和专家调度实现为独立影子图。Java 继续持有认证、SSE、业务事务、预算、幂等和权威状态。

## 6. 验证矩阵

每阶段至少执行：

1. 受影响模块的定向单元测试；
2. `mvn test`；
3. `mvn verify`；
4. 相关 PostgreSQL/Neo4j 集成测试；
5. `npm run lint`；
6. `npm run lint:types`；
7. `npm run build`；
8. `git diff --check`；
9. 检查未修改用户已有工作区文件。

涉及聊天、检索或模型行为时，再运行对应 Eval；纯结构移动不以真实 Provider 评测代替确定性测试。

## 7. 回滚规则

- 每个阶段只修改一个明确模块；
- 不混入功能开发、Schema 变更或依赖升级；
- 若无法通过原有测试，优先回滚该切片，不用额外 fallback 掩盖行为差异；
- 不删除旧实现，直到新实现的定向测试和全量测试全部通过；
- 对并发、取消、lease、幂等和授权路径，必须保留原 SQL 条件和终态判断。

## 8. 完成标准

本轮 Phase 1 完成需要满足：

- `PlanService` 不再直接依赖 `JdbcTemplate`、`@Scheduled`、`ExecutorService` 或 `Semaphore`；
- 计划模块 API JSON 结构保持不变；
- 计划生成任务仍使用相同认领、lease 与 fencing 语义；
- 新增业务规则和 Worker 回归测试；
- 后端全量测试、JaCoCo 门禁及前端静态检查通过；
- 本轮不触碰已有用户修改。

## Phase 2 progress (2026-09-03)

The first sub-slice of Phase 2 is complete: the structured JSON generation gateway now uses the provider-neutral `LlmMessage` contract. LangChain4j conversion is isolated in `LlmMessageMapper` and the `LlmGateway` adapter. Streaming generation remains unchanged and is intentionally deferred to a separate sub-slice because cancellation and SSE semantics are high risk.

Added regression coverage for message mapping, structured output, plan generation, tool loop, and the gateway provider/budget paths. An ArchUnit rule prevents gateway interfaces from depending on `dev.langchain4j`.
### Streaming contract progress (2026-09-03)

The streaming gateway now uses `LlmMessage`, `LlmStreamHandler`, and `LlmStreamResult`. LangChain4j streaming callbacks are confined to `LlmGateway` and its Provider adapter. SSE event names, token ordering, cancellation behavior, finish/truncation semantics, and synchronous completion behavior were preserved and covered by regression tests.