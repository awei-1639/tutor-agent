# 故障与 Badcase 卡片

## 卡片格式

遇到问题时按下面的顺序记录，方便别人复现，也方便以后把排查步骤自动化：

```text
现象：用户看到了什么，或指标哪里异常？
期望：正常结果是什么？
范围：单用户、单请求、单依赖还是全局？
证据：命令、时间、traceId、日志、截图或样本编号。
根因：已确认的原因；未确认时标注“假设”。
处理：修复、降级、重试、回滚或用户提示。
验收：如何证明问题已解决且没有扩大影响？
```

## Badcase 01：`/healthz` 正常，但 `/readyz` 返回 503

### 现象

进程可以访问，但就绪检查失败。

### 判断

`/healthz` 只回答进程是否存活；`/readyz` 还会检查关键依赖。数据库或 Neo4j 不可用时，二者返回不同结果是预期行为。

### 操作

1. 查看后端日志中的依赖名称和连接错误。
2. 检查 PostgreSQL、Neo4j 容器及端口。
3. 明确当前要验证的是“应用启动”还是“完整能力可用”。

### 通过标准

能解释差异，并且不会为了让探针变绿而删除真实依赖检查。

## Badcase 02：Neo4j 不可用，但聊天仍应可回答

### 现象

图谱连接失败，部分回答仍然应该返回；同时不能继续输出不存在的图谱证据。

### 判断

Neo4j 是检索链中的增强路径，不应成为所有问题的单点故障。熔断器负责快速失败和恢复探测，向量或其他安全路径负责有限降级。

### 操作

1. 确认图谱调用经过 `retrieval/resilience/Neo4jResilience.java`。
2. 观察熔断前、熔断中、恢复后的请求行为。
3. 检查最终引用是否只包含实际取得的证据。

### 通过标准

- 图谱不可用时响应仍有明确边界。
- 不伪造“由技能前置关系证明”的结论。
- 恢复后不会永久停留在降级状态。

## Badcase 03：RAG 总分高，但用户觉得答案不可靠

### 现象

总 Recall 或 MRR 不低，但某类问题经常引用错误章节、缺少证据或把不可回答问题答得过于肯定。

### 判断

聚合指标掩盖了分类差异。需要同时看文件、章节、证据锚点、不可回答和耗时，并回到具体样本。

### 操作

1. 按类别拆分结果。
2. 记录 gold 节点、实际命中节点和排名位置。
3. 将失败归入召回、排序、扩展、证据或回答边界问题。

### 通过标准

下一轮改动必须对应某一类失败，不能只凭总分“感觉优化了”。

## Badcase 04：评测页面没有结果或结果像演示数据

### 现象

前端页面空白、运行失败，或页面有数字但找不到测评集版本和样本。

### 判断

可视化只是结果载体，不是评测本身。先确认数据、后端接口、运行配置和结果持久化是否完整。

### 操作

1. 先执行 `node evals/run_eval.mjs --smoke`。
2. 检查 `evals/rag_testset.json` 是否存在、样本是否有 gold 证据。
3. 查看浏览器网络请求和后端评测日志。
4. 保存一次完整运行的配置、指标和失败样本。

### 通过标准

结果可追溯到测评集、配置和样本；否则只能标记为“未完成评测”。

## Badcase 05：测试在本机通过，在 WSL 或 CI 失败

### 现象

单元测试通过，但集成测试找不到 Docker、Java 版本不一致或数据库未就绪。

### 判断

默认 `mvn test` 与需要真实依赖的集成测试是两条验证路径，环境前置条件不同。

### 操作

1. 先确认 `java -version`、`mvn -version` 和 `docker version`。
2. 默认测试先运行：`mvn test`。
3. 需要真实依赖时按 README 显式运行 `mvn test -DrunIntegrationTests=true -Dtest='*IT'`。
4. 记录 WSL、Docker Desktop、端口和数据库初始化状态。

### 通过标准

报告中明确区分“单元/契约测试通过”和“真实依赖集成测试通过”。

## Badcase 06：out_of_scope 越界问题被判成 chat（已定位并修复）

### 现象

路由评测中，明显与学习/求职无关的问题没有被识别为越界，而是落进了 `chat`。
2026-08-27 本地评测（30 条路由集）实测样本：

- 「帮我写一篇朋友圈文案宣传我的奶茶店」→ 期望 out_of_scope，实际 chat
- 「今晚吃什么好，推荐几道家常菜」→ 期望 out_of_scope，实际 chat
- 「帮我翻译一段英文合同」→ 期望 out_of_scope，实际 chat
- 「明天北京天气怎么样」→ 期望 out_of_scope，实际 chat
- 「给我讲个笑话放松一下」→ 期望 out_of_scope，实际 chat

同轮 out_of_scope 行仅 5 条，全部误判为 chat（混淆矩阵 out_of_scope→chat=5）。

### 最初的假设（已被证伪）

原判断是「越界判定与闲聊语义接近 + `ROUTING_OUT_OF_SCOPE_THRESHOLD` 默认 0.92 偏高」，
属于模型能力/阈值问题。这个假设是错的：阈值和 prompt 都没问题，模型本身能正确
输出 `scope=out_of_scope`。

### 真实根因

`/internal/route` 把 traceId 写死成字面量 `"eval"`
（`InternalController.java:67`，2026-08-29 已修复）。`llm_turn_budget` 以 `trace_id`
为主键、只有 `created_at` 没有 TTL，于是**所有历史评测的 token 用量都累加到
`trace_id='eval'` 这一行**。该行涨到 48864，越过 `turn-token-limit: 50000` 后，
`LlmBudgetGuard.reserveTurn` 每次都抛「本轮 token 预算已用尽」，
`IntentRouter` 捕获后返回 `RouteDecision.degraded("ROUTER_UNAVAILABLE")` ——
即 `intent=CHAT, confidence=0`。

于是评测里每一条路由都变成 chat，不只是 out_of_scope 那 5 条。这个失败是静默的：
后端只打一行 `structured output provider failure type=IllegalStateException` WARN，
评测脚本照常输出指标，看起来像"模型判错"。

2026-08-29 重跑 30 条路由集验证：

| 指标 | 修复前 | 修复后 | 阈值 |
| --- | --- | --- | --- |
| Accuracy | 16.7% | 93.3% | 85% |
| Macro-F1 | 0.048 | 0.935 | 0.80 |
| out_of_scope 召回 | 0/5 | **5/5** | — |
| 领域内误判越界 | 0.0% | 0.0% | ≤5% |
| 预测标签种类 | 只有 chat | 6 类齐全 | — |

修复前 30 条的 confidence 全是 0；修复后为 0.7/0.85/0.95/0.98/0.99。

### 教训

1. **共享可变配额的 key 不能用固定字面量。** 任何以 trace/session 为主键、又没有
   TTL 的计数表，写死 key 等于把一个全局单调递增计数器伪装成单轮配额。
2. **降级路径必须在指标里可见。** `degraded=true` 和 `confidence=0` 当时就在
   `/internal/route` 的响应里，但评测脚本只统计 intent 是否相等，没有把
   degraded 比例作为独立指标输出。降级率应当是评测报告的一等指标。

### 遗留

3/30 仍误判（planning→chat ×2、mixed→chat ×1），是另一个原因：
路由 JSON 在 288 列附近被截断（`Unexpected end-of-input: was expecting closing
quote`），`structured_output_events` 里今天 19 条 `invalid`。这些样本的
`reason_codes` 是中文自然语言，加上 `llm.tokens.router.output-tokens: 96` 的上限
容易溢出。见 Badcase 07。

## Badcase 07：路由 JSON 被 output-tokens 上限截断

### 现象

`structured_output_events` 中 `task='router' AND validation_status='invalid'`，
错误恒为 `Unexpected end-of-input: was expecting closing quote for a string value
at ... column: 283~295`。首次尝试与修复重试（attempt 1、2）同样失败，
最终落到 `ROUTER_INVALID_JSON` 降级。

2026-08-29 30 条路由集里命中 3 条，全部是需要输出较长 `reason_codes` 的样本：

- 「每天只有2小时，怎么安排学习大模型开发」→ 期望 planning，实际 chat
- 「下个月开始准备秋招，学习节奏怎么定」→ 期望 planning，实际 chat
- 「帮我全面评估下现在离拿到算法offer还差什么」→ 期望 mixed，实际 chat

单独用 curl 打 `/internal/route` 时这两条能正确返回 `intent=planning`
`confidence=0.85`，说明不是稳定复现的语义错误，而是长度边界上的抖动。

### 判断（假设）

`llm.tokens.router.output-tokens: 96` 对当前 schema 太紧。RouterOutput 除了枚举
字段还要输出中文 `reason_codes`，一条中文理由就要十几个 token，两条理由 + 其余
字段就会逼近 96。截断点稳定落在 283~295 列，与"输出预算耗尽"而非"模型写错"一致。

### 操作

1. 查证：`SELECT validation_status, count(*) FROM structured_output_events
   WHERE task='router' GROUP BY 1;` 看 invalid 占比是否与失败样本数吻合。
2. 上调 `output-tokens`（96 → 160 左右）后重跑 30 条，看 invalid 是否归零、
   Accuracy 是否补上这 3 条。
3. 另一个方向：让 `reason_codes` 输出枚举 code 而不是中文自然语言句子，
   从源头压缩输出长度。这更根治，但要同步改 prompt、schema 和 trace 消费方。
4. 无论走哪条，都要确认领域内误判越界仍为 0.0%。

### 通过标准

- `structured_output_events` 中 router 的 `invalid` 归零或降到个位数占比。
- 30 条路由集 Accuracy ≥ 85%、Macro-F1 ≥ 0.80，且这 3 条具名样本判对。
- 评测报告中新增"路由降级率"指标，使同类静默降级下次能被直接看见。

## 如何提交一个好问题

不要只发“启动不了”“结果不对”。至少附上：

- 复现命令和运行环境；
- 期望与实际结果；
- 时间、接口、样本编号或 traceId；
- 已经排除的可能性；
- 你认为最可能的根因以及希望验证的下一步。
