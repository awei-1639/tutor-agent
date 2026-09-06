# 本地数据盘点与处置

> 更新：2026-09-06。回答"跑过的任务留下了什么真实数据、它们对项目有什么用、怎么处置"。

评测、探针和日常开发会在 Docker 数据卷、磁盘、Git 和外部服务里留下四类数据。
它们把决策从"感觉"变成"证据链"——本项目的通道取舍、成本控制、坏例定位都直接查这些数据得出。

## 1. Docker 数据卷（tutor-postgres / tutor-neo4j，最值钱）

### 知识库种子 —— 评测可信度的地基，丢了要重烧 embedding 费

| 表/图 | 规模 | 价值 |
|---|---|---|
| `kg_chunks` | 495 行（全部带 1024 维向量） | 检索评测的真实输入；Badcase 10 诊断靠它确认数据完好 |
| `jobs` | 202 行 | 岗位消歧（Badcase 10 剩余缺口）的直接素材 |
| Neo4j 图 | Job 202 / Resource 193 / Company 122 / Skill 100 节点，1146 条 REQUIRES 边 | 图扩展与多跳评测的地基 |

### 运行账本 —— 成本与故障的唯一事实源

| 表 | 规模 | 价值 |
|---|---|---|
| `llm_usage` | 万级 | 每次调用的 purpose/model/token/状态；成本结构分析（"router 占 82%"）、预算标定、故障定位都靠它 |
| `tool_calls` | 数千行 | 工具审计；Badcase 08 的超时证据 |
| `turn_traces` | 百级 | 负反馈归因 join 的数据源 |
| `eval_runs` | 每次评测 1 行 | git_sha + 指标 JSON，基线溯源的权威来源 |
| `users`/`conversations`/`messages`/`episodes`/`user_facts` | 少量 | 开发冒烟与 memory 评测播种残留，**没有真实用户数据**，无分析价值 |

## 2. 磁盘文件（不入 git）

- `evals/results/*.json`：每次评测的完整结果（含逐用例明细）。基线 diff 与回归对比的锚点；
- `_last_insert.sql`：`eval_runs` 入库语句留痕；
- WSL `/tmp` 的评测后端日志：临时性质，可随时清。

## 3. Git 里（结论的固化）

原始数据不入库，但从它们得出的结论以文档固化：`docs/phase5-retrieval-ablation-2026-09-05.md`
（通道决策）、`docs/refactoring-checkpoint-*.md`、`docs/badcases.md`（坏例证据链）、
`docs/chat-behavior-matrix-*.md`（行为契约）。

## 4. 外部服务

- GitHub：PR 与 CI 运行记录、`rag-eval-results` artifacts；
- DeepSeek / SiliconFlow 账户：token 消费流水（用 `scripts/llm-cost-report.mjs` 对账本地口径）。

## 日常处置

- **成本报表**：`node scripts/llm-cost-report.mjs [--days 7]`；
- **观测数据保留**：`node scripts/cleanup-observability-data.mjs [--days 90] [--apply]`
  （默认 dry-run；`llm_usage`/`tool_calls`/`turn_traces` 建议保留 90 天，先 dry-run 再 apply）；
- **基线归档**：`eval_runs` 与 `evals/results/` 是决策证据，**不在自动清理范围内**；
  翻版归档时把旧基线 JSON 移入 `evals/results/archive/` 并在提交信息里注明；
- **严禁** `docker compose down -v` 之后忘记重导种子（`scripts/eval-local.sh --force-seed` 会重烧 embedding 费）。
