# Facet 规则化 A/B 评测（2026-10-25）

关闭 #105 遗留的验证债：`tutor.retrieval.facet-rules-enabled` 开/关两种配置下，
同一环境（同 VM、同数据库卷、同 seed）背靠背跑全量 RAG 评测（280 条 × 4 模式 + router 30 条），
对比检索质量与 LLM 调用成本。

## 环境

- master @ 9270feb（#105 已合入），后端跑在 WSL 内与 Postgres/Neo4j 同 VM（避开 Windows→WSL 端口转发）。
- Postgres 卷 `agent_pg_data`（495 条已嵌入 chunk）、Neo4j 617 节点，未重新导入 seed。
- 查询嵌入 SiliconFlow `BAAI/bge-m3`（真实 API）；路由 LLM DeepSeek。
- 两次运行仅差 `TUTOR_RETRIEVAL_FACET_RULES_ENABLED=true/false`，其余配置与环境完全一致。
- 结果文件（本地，目录不入库）：`evals/results/eval_2026-10-25T04-28-15-357Z.json`（A=规则开）、
  `eval_2026-10-25T04-39-19-462Z.json`（B=规则关，provider 限流中）、
  `eval_2026-10-25T04-44-05-119Z.json`（B 重跑，provider 完全不可用，作废）。

## 结果

| 指标 | A（规则开） | B（规则关） | 改动前全量基线 10/05 |
| --- | --- | --- | --- |
| vector_only hit@5 / MRR | 0.636 / 0.451 | 0.636 / 0.451 | — |
| fused hit@5 / MRR | **0.725 / 0.556** | 0.636 / 0.451 | 0.771 / 0.555 |
| fused multi_hop hit@5 / MRR | **0.750 / 0.521** | 0.487 / 0.184 | 0.512 / 0.214 |
| fused p50 | 236ms | 394ms | 282ms |
| 检索路径路由 LLM 调用 | **0** | ~890/轮（920 次 ROUTER 失败告警） | 每次检索 1 次 |

vector_only 两组逐位相等，作对照组证明环境确定性成立。

## 结论

1. **facet 关键分片（multi_hop_prereq）显著改善**：0.750/0.521 对比 B 的 0.487/0.184，
   也超过改动前健康 provider 基线（10/05）的 0.512/0.214。图扩展在规则 facet 下工作得更好。
2. **鲁棒性是更大的发现**：B 组每条检索付一次路由调用，~890 次/轮打满 DeepSeek 限流后
   路由静默降级——B 的 fused 与其自身 vector_only 完全相等（0.636=0.636），multi_hop 同样
   （0.487=0.487），即图扩展整体失效；重跑 B 时 provider 彻底不可用，全模式归零。
   规则路径零路由调用，对 provider 故障免疫（A 轮检索阶段零 ROUTER 告警）。
   这与 badcases.md Badcase 06「预算越界后路由静默降级」是同一类故障模式。
3. ~~**总体 hit@5 与 10/05 的差距（0.725 vs 0.771）与 facet 无关**：job_requirement 分片从
   10/05 的 0.800 跌到本日 0.133（A/B 同跌，vector_only 同跌），属 gold/数据漂移，
   应另立 badcase 追查，不影响本次 A/B 结论。~~
   **（本条已推翻，见下方「补录：根因与修复」——不是数据漂移，是 facet 规则的盲区。）**
4. **成本**：一轮全量评测（280×4）下规则路径省 ~890 次路由调用；这不是纸面推算而是
   本次 A/B 实测的调用量差。

## 局限

- B 组未取得"provider 健康"当日的干净数据；B 的数字是旧行为的下界。
  若需补测，选 DeepSeek 限流恢复后的窗口重跑 `TUTOR_RETRIEVAL_FACET_RULES_ENABLED=false` 一轮即可，
  预期介于 B 与 10/05 基线之间。
- B 组重跑（B2）全零的成因事后修正：当日 2M token 预算被 A+B1 消耗殆尽（llm_daily_budget
  actual_tokens=1,998,558），BudgetExhausted 拒绝所有调用、检索降级稀疏——是预算守卫按设计工作
  （Badcase 06 模式），并非"provider 彻底不可用"。后续复现评测需带
  `LLM_BUDGET_DAILYTOKENLIMIT`（注意前缀是 `llm` 不是 `tutor.llm`，kebab-case 环境变量须去连字符）。
- A 组 agentic 阶段的 judge 调用全部失败（50/50，同一次限流事件），agentic 靠降级路径仍拿到
  0.960/0.837，说明降级设计有效，但该数字不能归功于 facet。
- 今日 router facet exact-match 26.7%（LLM 侧，#105 的动因之一），与 PR 描述的近期劣化一致。

## 补录：job_requirement 劣化的根因与修复（同日）

追查推翻了结论 3 的"数据漂移"判断。分模式对比锁定信号：job_requirement 的 vector_only
两天完全一致（0.100=0.100），跌掉的全是图扩展贡献——即 **facet 规则没有为这类查询打出 CAREER**。

- 形态：rag_testset 的 60 条 job_requirement 是「公司 + 岗位名 + 要求动词」的口语提问
  （如"腾讯做对话系统的NLP算法工程师，得会啥技能啊？"），不含原 CAREER 正则的任何关键词；
  "技能/掌握"反而命中 LEARNING → 图扩展走技能依赖入边，而 gold 挂在岗位出边（REQUIRES/LEADS_TO）上。
- 模拟验证：原规则 60 条只命中 1 条 CAREER（1.7%）。
- 修复：`RetrievalFacetRules` 新增「岗位主体词 + 要求动词」联合判定（`JOB_TITLE` ∧ `JOB_REQUIREMENT`）。
  要求动词是必要条件——「NLP算法工程师的笔试题」只有主体没有诉求，仍判 learning，标注语料不被破坏。
  修复后模拟 60/60 命中、30 条标注语料全部保持；单测语料扩至 35 条（+5 条该形态用例）。
- 这是 #105 作者自认"过拟合风险"的实例化：30 条标注集里没有这种问法，规则因此漏了一整类。
- 修复后的活体全量复测当日未能完成：下午 SiliconFlow 对持续并发嵌入限流（当日累计 ~7000 次调用），
  单发探测正常、评测并发下嵌入超时（`ToolExecutionException: 工具执行超时`），三轮全量与一轮 10 条抽样
  均被阻断；期间另确认 dockerd 曾间歇重启加剧连接抖动。复测方法：预算恢复后（或带
  `LLM_BUDGET_DAILYTOKENLIMIT` 覆盖）在新的一天窗口重跑全量评测，观察 job_requirement 分片
  从 0.133 回升至 0.800 一线（10/05 健康 LLM 路由的机制等价物）。
