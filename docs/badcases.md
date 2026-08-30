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
quote`），`structured_output_events` 里 19 条 `invalid`。这些样本的
`reason_codes` 是中文自然语言，加上 `llm.tokens.router.output-tokens: 96` 的上限
容易溢出。见 Badcase 07（已修复：上调到 160 后 Accuracy 96.7%、invalid 归零）。

## Badcase 07：路由 JSON 被 output-tokens 上限截断（已修复）

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

### 根因

`llm.tokens.router.output-tokens: 96` 对当前 schema 太紧。按截断点第 288 列估算：
JSON 骨架（枚举字段名 + 引号，纯 ASCII）已占约 70 token，留给 `reason_codes`
中文理由的只剩约 26 token，而一条中文理由就要十几个 token。截断点稳定落在
283~295 列，与"输出预算耗尽"而非"模型写错"一致——模型写到一半没配额了，
JSON 断在字符串中间。

### 处理

`output-tokens: 96 → 160`（`application.yml:234`）。2026-08-30 重跑 30 条：

| 指标 | 96 | 160 | 阈值 |
| --- | --- | --- | --- |
| Accuracy | 93.3% | **96.7%** | 85% |
| Macro-F1 | 0.935 | **0.966** | 0.80 |
| schema invalid | 19 | **0** | — |
| degraded (confidence=0) | 3 | **0** | — |
| 领域内误判越界 | 0.0% | 0.0% | ≤5% |

本卡片列出的 3 条具名样本全部判对。剩 1 条误判 mixed→planning（confidence 0.85，
非降级）：「帮我全面评估下现在离拿到算法offer还差什么」，属于真实的标签边界分歧，
不是工程缺陷——该问题确实同时涉及 planning 与 mixed，需要先确认标注口径。

更根治的方向仍然存在：让 `reason_codes` 输出枚举 code 而非中文自然语言句子，
从源头压缩输出长度，也让 trace 更容易聚合。这需要同步改 prompt、schema 和
trace 消费方，未在本次处理范围内。

### 教训

结构化输出的 `output-tokens` 上限是**正确性约束，不只是成本约束**。schema 越严格
（必填字段越多、枚举名越长），骨架占用的固定开销就越大，留给可变内容的余量越小。
调这个值之前应当按 schema 骨架长度估算下界，而不是凭"路由是个小任务"的直觉设定。

## Badcase 08：全量评测跑到第四个模式时整轮崩掉

### 现象

`bash scripts/eval-local.sh --retrieval`（280 条 × 4 模式）在 vector_only、fused、
fused_rerank 三个模式各 280 条全部跑完之后，`agentic` 的**第一条**请求挂满 90 秒
超时，Node 进程抛 `DOMException [TimeoutError]` 退出，整轮不产出结果文件：

```
DOMException [TimeoutError]: The operation was aborted due to timeout
    at async post (evals/run_eval.mjs:28:15)
    at async evalMode (evals/run_eval.mjs:58:15)
```

`eval-local.sh` 随后打印「本次未产出新的检索结果文件，跳过基线对比」并以 exit 0
结束——**失败被吞掉，脚本看起来是成功的**。

### 判断（已排除的可能）

不是 agentic 这一模式本身慢，也不是那条 case 有问题。用**全新 JVM** 单独跑同一批
280 条 agentic：

```
完成 280 条, 失败 0 条
P50=4105ms  P95=5605ms  max=8497ms
前 50 条 P50=4471ms   后 50 条 P50=4747ms
```

90 秒超时有 10 倍余量；前后半段 P50 只差 276ms，**没有随运行时间劣化**，所以也
不是连接池或 executor 泄漏。最慢的一条恰好是 #1（8497ms，JVM 未预热）。

结论：是前三个模式累计 840 次请求之后留下的某种累积状态，只在模式切换的瞬间
影响到第一条请求。具体成因尚未定位——`LlmConcurrencyGate` 只有 16 个许可且
`tryAcquire` 仅等 5 秒，是一个候选解释，但未经证实。

### 操作

已做：给 `evals/run_eval.mjs` 的 `post()` 加有限退避重试（2s、8s），4xx 立即抛出
不重试，只对 5xx 与超时重试。这让评测不再把一次瞬时故障放大成「整轮无结果」，
但**不是根因修复**。

待做：
1. 定位累积状态的真实来源。在 `LlmConcurrencyGate.acquire()` 失败时记录当前
   in-flight 许可数，或在 `evalMode` 之间打一次 `/actuator` 快照。
2. `eval-local.sh` 在评测脚本非零退出时应当自己也非零退出，而不是打一行提示后
   exit 0。当前行为会让 CI 把失败当成功。

### 通过标准

- 全量 4 模式能连续跑完并产出结果文件。
- 重试被触发时在日志中可见（已实现：打印第几次失败与退避时长），便于区分
  「一次瞬时抖动」与「稳定失败被重试掩盖」。

## Badcase 09：facet 指标不达标，且改 prompt 让它更糟

### 现象

30 条路由集上 facet Exact-Match 66.7%（阈值 85%）、Macro-F1 0.754（阈值 0.80）。
这是 Badcase 06/07 修完后**唯一剩下的真实质量缺口**，也是 `rag-eval.yml`
仍为 report-only 的唯一原因。

10 条判错全部落在「intent 判对、facet 判错」这一格：

```
intent 对 + facet 对    19
intent 对 + facet 错    10
intent 错 + facet 对     1
intent 错 + facet 错     0
```

错误方向以「多给」为主：多给 7 条、少给 2 条、换掉 1 条。

`RoutingPolicy.plan()` 对 facet 是原样透传
（`trustedInScope ? decision.retrievalFacets() : List.of()`，`RoutingPolicy.java:138`），
**没有任何映射逻辑**，所以不存在「策略层算错」这种可改的代码路径——分歧全部
在模型输出与人工标注之间。

### 判断

标注口径此前从未成文：`router_testset.json` 的 note 只有一句
「意图路由标注语料, 人工标注 (30条, 每类5条)」，`docs/` 里搜不到 facet 判定标准。
而 `docs/evaluation.md` 已经按这套未文档化的标注设了 85%/0.80 的发布门禁。

分歧样本大多是真正可争的边界，而非模型犯蠢：

- 「明天要面试大模型岗，帮我模拟一下」→ 标注 `learning`，模型给 `career,learning`
- 「西瓜书适合入门看吗」→ 标注 `resource`，模型给 `learning,resource`
- 「什么是检索增强生成」→ 标注 `[]`，模型给 `learning`

### 操作与结果

**第一步：先写口径，不动代码。** 新增 `docs/facet-annotation-criteria.md`，把口径
锚定在 `GraphExpansionPolicy.forFacets` 的机械行为上（career→`REQUIRES`/`LEADS_TO`
出边，learning→`PREREQUISITE`/`TEACHES` 入边，resource→`TEACHES` 入边），
而不是凭「话题像什么」。其中一条关键推论：**`learning` 已包含 `TEACHES`**
（权重 0.90），所以 `learning`+`resource` 并列只是把权重提到 1.0，不是新增召回
通道——这类叠加应当避免。

**第二步：按口径逐条复核 10 条分歧。** 结果是 **9 条标注正确、只有 1 条该改**：
「面试官问我为什么转行，该怎么回答比较好」原标 `learning`，但这是沟通话术，
图谱里没有任何关系能支撑它，应为 `[]`。测试集升到 v2，只改这一条。

**第三步（失败的尝试）：把口径写进 router prompt。** 把上述规则（含机械含义、
不要为沾边叠加、期限词不算 career 等）扩写进 `IntentRouter.SYS` 后重测：

| 指标 | 原 prompt | 扩写 prompt 后 |
| --- | --- | --- |
| facet Exact-Match | 20/30 | **10/30** |
| intent 判对 | 29/30 | **22/30** |

**两项都大幅变差，已完整回滚。** 更长更细的 facet 指令不仅没提升 facet，还把
intent 判断带偏了（planning→chat、mixed→planning 等新错误），并出现原先没有的
`career,learning,resource` 三连叠加。推测是指令过长挤占了注意力，且新引入的
`output-tokens` 压力（Badcase 07）也可能参与其中。

### 当前状态与遗留

- 口径已成文（`docs/facet-annotation-criteria.md`），标注 v2 只改 1 条。
- facet 指标仍不达标，**代码与 prompt 均未改动**。
- `rag-eval.yml` 保持 report-only，理由已更正为「facet 未达标 + embedding 抖动」。

下一步的候选，按我判断的优先级：
1. **扩充标注集**。30 条里 facet 组合分布极不均衡（`resource` 只有 1 条），
   Macro-F1 对小类极其敏感，单条判错就掉一大截。先把每种 facet 组合补到 5 条
   以上，再看指标是否仍不达标——现在的 0.754 有多少是样本量噪声还说不清。
2. **考虑 few-shot 而非长指令**。第三步证明加长自然语言规则会反噬；给 3~5 个
   带标注的示例可能比讲道理有效。
3. **重新审视阈值本身**。85% Exact-Match 要求 30 条里最多错 4 条，而边界样本
   本身就有真实歧义。若口径成文后人工复标仍与模型稳定分歧，该调的可能是阈值。

不要在这三步之前改 prompt 去迎合 30 条标注——那是朝测试集过拟合，第三步已经
演示了它的代价。

## 如何提交一个好问题

不要只发“启动不了”“结果不对”。至少附上：

- 复现命令和运行环境；
- 期望与实际结果；
- 时间、接口、样本编号或 traceId；
- 已经排除的可能性；
- 你认为最可能的根因以及希望验证的下一步。
