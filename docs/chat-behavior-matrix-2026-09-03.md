# Chat 行为特征矩阵

日期：2026-09-03

本矩阵用于 Chat 结构重构。重构前后必须保持“可观察行为”一致；内部类名、包名和实现方式可以变化，但以下结果不能静默变化。

## 1. 组件职责边界

```text
ChatController
  ├─ 认证后的请求入口
  ├─ SSE 建立和事件转发
  └─ 客户端取消回调

ChatTurnService
  ├─ 回合准入和幂等
  ├─ 用户消息持久化
  ├─ RUNNING 租约和 fencing
  ├─ 恢复 Worker
  └─ 回合最终状态

ChatService
  ├─ 一次回合的上下文和决策编排
  ├─ 路由、检索和回答分流
  ├─ 引用收口
  └─ 将结果交回 ChatTurnService 或 ConversationStore
```

`ChatTurnService` 是状态机权威，`ChatService` 不得自行改变 durable turn 的最终状态。`ChatService` 的抽取不能改变两者之间的调用顺序。

## 2. 主流程行为

| 阶段 | 可观察行为 | 关键依赖 | 当前测试 |
|---|---|---|---|
| admission | 回合请求按 requestId 幂等；同一会话并发受限 | ChatTurnService, PostgreSQL | ChatTurnServiceTest, ChatTurnPostgresIT |
| context | 固定 userId、conversationId、memory generation；配额不足时不写用户消息 | AuthContext, BudgetGuard, ConversationStore | ChatServiceBudgetSheddingTest |
| rewrite | 代词消解和短追问补全；歧义时进入 clarify | ContextualQueryRewriter | ChatServiceTurnPathsTest |
| route | 记录原始决策和生效计划；非法/失败时安全降级 | IntentRouter, RoutingPolicy | IntentRouterTest, ChatServiceTurnPathsTest |
| memory | 召回 Episode/Fact 并通过 memories 事件暴露实际使用内容 | LongTermMemoryService, FactRecallService | ChatServiceTurnPathsTest |
| retrieve | out_of_scope 跳过；图谱/Embedding/Rerank 失败按既有策略降级 | AgenticRetriever, GraphScope | AgenticRetrieverTest, FusedRetrieverTest |
| direct | 保留原问题和筛选历史；流式 token 按顺序发送 | StreamingGateway, ToolCallLoop | ChatServiceTest, ChatServiceTurnPathsTest |
| expert | 专家按策略扇出；取消后不启动聚合；部分失败可继续 | ExpertRunner, Aggregator | ExpertRunnerTest, ChatServiceTurnPathsTest |
| guard | 引用映射和状态统一收口；无租约不得发 done | TurnCitations, CitationVerification, ChatTurnService | CitationGuardTest, ChatTurnPostgresIT |
| finalize | 写 assistant message、发 done、启动后置任务 | ConversationStore, PostTurnTaskService | ChatServiceTest, ChatTurnPostgresIT |
| failure | 用户只看到稳定 code/message；内部细节只进日志 | Budget/Llm exceptions | ChatServiceTurnPathsTest |

## 3. 事件契约

当前 SSE 相关事件：

```text
meta
stage
memories
citation
token
clarify
done
error
```

必须保持：

- `meta` 在该回合开始时发送，并携带 conversation/trace 信息；
- `stage` 不得因为内部类拆分重复发送；
- `token` 按供应商返回顺序发送，不发送取消后的 token；
- `citation` 只包含本轮真实取得的证据；
- `clarify` 与 `done` 的顺序保持当前实现；
- `done` 只有在数据库完成提交且 lease 仍有效时发送；
- `error` 使用稳定机器码和用户文案，不透传内部异常细节。

## 4. 回合终态矩阵

| 情况 | 数据库终态 | 是否 assistant message | 是否 done | 是否 error |
|---|---|---:|---:|---:|
| 正常直答 | COMPLETED | 是 | 是 | 否 |
| 正常专家聚合 | COMPLETED | 是 | 是 | 否 |
| 澄清 | COMPLETED/待澄清状态 | 是 | 是 | 否 |
| 用户取消 | CANCELLED | 否 | 否 | 否 |
| lease 丢失 | 原 worker 不得提交 | 否 | 否 | 不由旧 worker 发送 |
| 预算耗尽 | FAILED 或既有回合策略 | 否 | 否 | 是，稳定 code |
| Provider 失败 | FAILED 或既有降级结果 | 视现有路径 | 视现有路径 | 是或安全降级 |
| 客户端断开 | 取消底层流；持久化按当前 durable 语义 | 不得产生迟到提交 | 不向断开客户端发送 | 不向断开客户端发送 |

## 5. 不允许的重构副作用

- 将 ChatService 的错误处理移动后重新把内部异常消息发给用户；
- 将 `ChatTurnService` 的 lease/fencing 逻辑复制到 ChatService；
- 为了抽取上下文而改变记忆召回或 Prompt 内容；
- 为了引入 TurnState 而把数据库实体、SSE 事件和 LLM Provider 类型放进同一个 State；
- 将后台后置任务改成请求线程同步执行；
- 将流式同步等待改成 fire-and-forget；
- 将任何核心业务写操作交给 LangGraph 或模型节点直接执行。

## 6. 抽取顺序

必须按以下顺序逐步抽取：

1. 先引入只包含输入和中间结果的 `TurnState`，不改变流程；
2. 抽取纯上下文读取/组装，不包含写入和 SSE；
3. 抽取路由与预算降级，保持原始决策和生效计划同时传递；
4. 抽取检索阶段，保持记忆事件和 citation 事件顺序；
5. 抽取回答分流，保持直答、工具、专家三条路径；
6. 最后抽取完成收口，确保 durable turn 提交仍由原状态权威完成。

每一步只能引入一个新的边界，并必须先通过本矩阵相关测试，再进行下一步。

## 7. 第一批允许的结构变更

第一批只允许：

- 新增 `TurnState` 或等价的包内模型；
- 将纯函数式的 trace/map/边界转换移出 ChatService；
- 将不会触碰数据库和 SSE 的上下文计算抽出；
- 增加架构测试和行为特征测试。

第一批不允许：

- 移动整个 `chat` 包；
- 改造 ChatTurnService 状态机；
- 替换消息协议；
- 修改数据库；
- 接入 LangGraph；
- 优化 Prompt 或更换模型。
