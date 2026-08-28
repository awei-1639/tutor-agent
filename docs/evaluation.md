# RAG 评测手册

## 1. 评测目标

评测关注的是检索是否把正确证据带进 TopK，而不是只看最终回答是否“像人话”。项目使用带 Gold 节点的回归集，并比较不同检索管线：

| 模式 | 含义 |
| --- | --- |
| `vector_only` | 只使用 pgvector 向量召回 |
| `fused` | 向量 + pg_trgm + Neo4j 图扩展 + RRF |
| `fused_rerank` | 混合召回后，对适合资源推荐的 Query 做二阶段重排 |
| `agentic` | 受控多跳检索，最多 3 Hop |

## 2. 测评集格式

文件：`evals/rag_testset.json`。

```json
{
  "version": 1,
  "cases": [
    {
      "id": "q001",
      "type": "single_hop_skill",
      "gold": ["skill:message-queue"],
      "gold_intent": "learn",
      "query": "消息队列里的分区到底是啥意思啊？"
    }
  ]
}
```

字段要求：`id` 是稳定用例 ID；`type` 用于切片分析；`gold` 是人工确认的正确节点 ID；`gold_intent` 是目标检索意图；`query` 是用户可能输入的自然语言问题。

当前 v1 是项目领域回归集，共 280 条：单跳技能 60 条、资源推荐 80 条、岗位要求 60 条、多跳前置 80 条。它不是未经授权的真实用户数据集；如果发布真实用户报告，应另建脱敏并取得授权的数据集。

## 3. 指标定义

对每条样本，系统得到 TopK 节点列表 `retrieved` 和 Gold 集合 `gold`。

### Recall@K

```text
Recall@K = |retrieved[:K] ∩ gold| / |gold|
```

它衡量 Gold 节点召回了多少，适合多目标岗位要求和多跳前置场景。

### Hit@K

```text
Hit@K = 1  当 TopK 至少命中一个 Gold
        0  否则
```

总体 Hit@K 是所有样本 Hit 值的平均值，也就是案例通过率。

### MRR@K

```text
RR = 1 / 第一个 Gold 节点的排名
MRR = 所有样本 RR 的平均值
```

第 1 名命中得 1，第 2 名命中得 0.5；TopK 内没有 Gold 时为 0。它关注第一个正确证据是否靠前。

### 延迟

记录每条检索耗时，并输出 P50、P95。质量提升不能用不可接受的延迟换取，尤其是 `agentic` 模式。

## 4. 运行方式

确保后端已启动，并且 `INTERNAL_ENDPOINTS_ENABLED=true`：

```powershell
node evals/run_eval.mjs --smoke
node evals/run_eval.mjs
node evals/run_eval.mjs --ci
```

`--smoke` 只跑少量样本，用于验证依赖和链路；完整 Golden Set 才能作为发布门禁。结果写入 `evals/results/`，并尝试写入 PostgreSQL 的 `eval_runs`。

同一脚本还会调用真实的 `POST /internal/route`。路由结果除了总体 Accuracy，还会输出意图与检索 facet 的 Precision/Recall/F1、Macro-F1、混淆矩阵，以及“领域内问题误判为越界”的比例。`--ci` 模式下，路由 Accuracy、意图/Facet Macro-F1、Facet Exact-Match 和越界假阴性率任一不达标都会阻断发布；`--skip-router` 仅适用于检索链路诊断。

启动前端后打开 RAG 评测页面，页面支持启动真实评测、查看运行历史、总体指标、分类切片、历史基线和失败用例根因。

后端评测接口：

```text
POST /internal/evals
GET  /internal/evals
GET  /internal/evals/{id}
```

生产 profile 会关闭 `/internal/*`，不要把评测端点暴露到公网。

## 5. 质量门禁

面试评分双人标注的实际操作步骤、评分锚点、API 示例和 CI 数据格式见[面试评分双人标注教程](./interview-score-annotation-tutorial.md)。

评测服务区分两类门禁：

- P0：执行可靠性，评测错误数不能超过 `RAG_EVAL_MAX_ERRORS`；
- P1：检索质量，总体 Hit、总体 Recall、多跳 Hit 需要达到阈值。

默认配置：

```text
RAG_EVAL_MIN_OVERALL_HIT=0.70
RAG_EVAL_MIN_OVERALL_RECALL=0.40
RAG_EVAL_MIN_MULTI_HOP_HIT=0.50
RAG_EVAL_MAX_ERRORS=0
```

路由门禁默认值：

```text
router_accuracy >= 0.85
router_macro_f1 >= 0.80
router_facet_exact_match >= 0.85
router_facet_macro_f1 >= 0.80
in_scope_to_out_of_scope <= 0.05
```

完整 Golden Set 通过质量门禁后才标记为 `releaseEligible=true`。抽样运行只能用于诊断，状态会是 `sample_only`，不能作为发布证明。

Hit 指标同时输出 Wilson 95% 置信区间，避免把小样本的偶然波动当成真实提升。

## 6. Badcase 处理流程

```text
失败用例
  ↓
读取 retrieved / gold / type / graph_path
  ↓
根因聚类
  ├─ gold_not_retrieved
  ├─ multi_hop_miss
  ├─ job_skill_coverage_gap
  ├─ resource_type_mismatch
  └─ execution_error
  ↓
指定 Owner 和修复建议
  ↓
只修改一个变量或一组相关变量
  ↓
重跑同一版本数据集并比较基线
```

修改数据集时必须说明新增、删除或调整了哪些 Gold 节点及原因，否则指标变化无法区分“系统变好”和“题目变容易”。

## 7. 引用忠实度评估

`evals/run_citation_eval.mjs` 会让真实 `/chat` 产生 SSE 回答，抽取带 `[S#]` 的句子，再使用异源模型判断结论是否被对应证据支撑。

该脚本目前是辅助基线，不是唯一真值，因为裁判模型尚未完成充分人工校准。正式报告应同时记录 Query 子集、回答模型、裁判模型、评测时间、知识库快照、人工抽查样本以及不支撑结论的原句和证据。
# 面试评分契约回归

`GET /internal/interview-evals` 会执行版本化的人工期望分数样本集，输出平均绝对误差、三级评分一致率和明显错误高分误判率。

真实模型评测使用 `POST /internal/interview-evals/replay`，请求体格式为：

```json
{
  "datasetVersion": "interview-human-gold-v1",
  "cases": [
    {"id": "case-001", "humanScore": 8, "modelScore": 7, "modelConfidence": 0.82, "reviewerCount": 2, "humanScoreSpread": 1}
  ]
}
```

评测数据应来自脱敏后的实际模型输出和至少两名人工标注者的复审结果；不要把确定性规则基线的分数当作模型分数上传。

管理员可通过 `GET /admin/interview-evals/annotations/queue?minReviewers=2&maxPerSession=1` 获取待复核的已收卷样本，再通过 `POST /admin/interview-evals/annotations/{questionId}` 提交人工分数和复核理由。队列会优先排列用户标记为“评分不准确”和模型低置信度的样本，但默认 `blind=true`，评审提交前看不到模型分数、置信度和反馈信号，避免锚定偏差；只有受控复盘时才显式使用 `blind=false`。队列会排除当前评审人已经标注过的题，并默认每个 session 每次最多取 1 题，确保双标样本来自独立评审且不被单个面试主导。队列只返回脱敏后的问题和回答，不返回候选人、用户或会话身份；同一评审人重复提交同一题会幂等更新。查看队列、提交标注和导出 replay 都会写入 `admin_audit_log`，但审计元数据不包含回答原文。生产环境仍应取得标注授权并设置保留期限。

当同一题达到至少两名评审后，可调用 `POST /admin/interview-evals/annotations/replay`（如 `{"datasetVersion":"human-gold-v1","minReviewers":2}`），系统会按题目聚合人工分数并直接写入一条 replay 评测运行记录。发布门禁要求所有样本达到双人标注；同时限制人工评审分歧率不超过 20%，避免平均分掩盖评审意见冲突。

重放结果会写入 `interview_score_eval_runs`，可通过 `GET /internal/interview-evals/runs` 查看历史，或通过 `GET /internal/interview-evals/runs/{id}` 查看单次指标。数据库只保存分数、置信度和聚合指标，不保存回答原文。

CI 或本地可用 `node evals/run_interview_score_eval.mjs --input <脱敏 JSON> --min-reviewers 2 --ci` 重复执行同一门禁。脚本会归档带 Git SHA、数据集版本和规则结果的 JSON；门禁不通过时返回非零退出码。

仓库 CI 支持通过受保护 secret `INTERVIEW_SCORE_GOLD_JSON_B64` 注入授权的 gold JSON。配置 secret 后，后端 job 会临时解码文件并执行 `InterviewScoreEvalGateTest`；未配置时只发出 notice 并跳过，不会把“没有真实数据”误报成门禁通过。runner 完成后会删除临时文件。

当前结果的 `kind=deterministic_contract_baseline`：它验证题目评分契约、关键词证据规则和回归门禁，**不代表 LLM 与人工评分的一致性**。在接入双人标注样本、模型实际输出和标注分歧复审之前，不能将它用作“面试评分准确率”的对外结论。
