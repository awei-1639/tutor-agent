# Evals

本目录保存 RAG、意图路由和引用质量评测资料。

## 内容

- `rag_testset.json`：带 Gold 节点的 RAG 回归集；
- `router_testset.json`：意图路由标注集；
- `memory_testset.json`：记忆召回评测集（事实命中/superseded 泄漏/时序正确性/无关注入）；
- `run_eval.mjs`：比较四条真实检索管线；
- `run_memory_eval.mjs`：记忆召回评测（打 `/internal/memory-recall`，种子经 `/internal/memory-seed` 重建，embedding 走真实网关）；
- `run_citation_eval.mjs`：辅助引用忠实度评估；
- `run_interview_score_eval.mjs`：重放脱敏的模型—人工评分记录并执行双人标注门禁；
- `results/`：运行产生的 JSON 结果，默认不应提交。

评测必须调用后端真实检索端点，不复制线上排序逻辑。指标和发布门禁见 [RAG 评测手册](../docs/evaluation.md)。

## 命令

```powershell
node evals/run_eval.mjs --smoke
node evals/run_eval.mjs
node evals/run_eval.mjs --ci
```

记忆评测（需要后端启动且配置了 embedding 供应商；`superseded_leak_rate` 阈值为 0，失效事实回流视为正确性缺陷）：

```powershell
node evals/run_memory_eval.mjs --smoke
node evals/run_memory_eval.mjs --ci
```

路由评测结果会保留逐条 `calibration_samples`，可用真实标注结果拟合越界校准模型：

```powershell
node scripts/collect-routing-calibration.mjs
node scripts/fit-routing-calibration.mjs `
  .\evals\results\router_calibration_<timestamp>.json `
  --output .\backend\src\main\resources\routing\isotonic-oos-v1.json `
  --version routing-oos-isotonic-dev-v1
```

训练脚本默认要求至少 50 条“预测为越界”的样本，并且同时包含真阳性和误判反例；只有开发验证时才允许加 `--allow-small`。仓库现有 30 条路由用例仅适合生成开发模型，不足以作为生产校准依据。

面试评分 replay 不会再次调用模型。输入可以是 `ReplayRequest` 对象，也可以直接是 cases 数组；每条记录必须带 `reviewerCount`，并达到至少两名独立评审：

```powershell
node evals/run_interview_score_eval.mjs `
  --input .\evals\private\interview-score-gold-v1.json `
  --dataset-version interview-human-gold-v1 `
  --min-reviewers 2 `
  --ci
```

脚本默认请求本地 `http://localhost:8180/internal/interview-evals/replay`，可通过 `INTERVIEW_EVAL_BASE_URL` 覆盖；`--ci` 在 `releaseEligible=false` 时返回非零退出码。输入文件只能使用已取得授权且脱敏的模型输出，脚本不会把原始回答写入结果，后端仅持久化分数与聚合指标。

GitHub Actions 可将同一份 JSON 以 base64 放入受保护 secret `INTERVIEW_SCORE_GOLD_JSON_B64`。CI 会在 runner 临时解码后执行 `InterviewScoreEvalGateTest`，任务结束删除文件；未配置 secret 时会明确跳过该门禁。

## 数据修改规则

新增或修改 Gold 节点时，需要记录用例原因、数据来源和预期影响；不能为了提高指标而只修改标签而不说明知识库变化。
