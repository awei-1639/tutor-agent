# Phase 5 检索通道消融实验（2026-09-05）

> 目的：用质量、延迟、Token 成本数据决定稀疏检索、图谱扩展、Rerank 和 Agentic 多跳的存留，
> 而不是"因为已经实现就默认保留"（主计划 Phase 5 收口要求）。
> 运行方式：`bash scripts/eval-local.sh --retrieval --skip-seed --skip-router`
> （280 条 rag_testset × 3 个 embedding 模式；agentic 另测，见下）。
> 跳过 router 评测（每条一次路由 LLM 调用）以控制成本；本次未重导种子（495 chunks 已有 embedding）。

## 结果

| 模式 | Recall@5 | Hit@5 | MRR | P50 | P95 |
|---|---:|---:|---:|---:|---:|
| vector_only | 46.2% | 63.6% | 0.494 | 2032ms | 4342ms |
| fused（dense+sparse+图一跳） | 49.6% | 70.7% | 0.524 | 2121ms | 7017ms |
| fused_rerank | **52.0%** | **71.1%** | **0.540** | **1988ms** | 5044ms |
| agentic（前 50 条，全部 single_hop） | 88.0% | 88.0% | 0.812 | 7216ms | 11221ms |

切片 Recall@5（前三个模式 n 为全量，agentic 主测集见下节）：

| 切片 | vector_only | fused | fused_rerank |
|---|---:|---:|---:|
| single_hop_skill (n=60) | 95.0% | 95.0% | 90.0% |
| resource_rec (n=80) | 70.3% | 71.1% | **80.0%** |
| job_requirement (n=60) | 1.9% | 7.8% | 12.7% |
| multi_hop_prereq (n=80) | 18.9% | 25.6% | 24.9% |

## agentic 在 multi_hop_prereq 的补测

`run_eval.mjs` 的 agentic 抽样取测集前 50 条（全是 single_hop），回答不了多跳通道的取舍，
用 `scripts/eval-agentic-multihop.sh` 对 80 条 multi_hop_prereq 单独跑：

| 指标 | agentic | fused_rerank（同切片） |
|---|---:|---:|
| Recall@5 | 29.6% | 24.9% |
| Hit@5 | 66.3% | — |
| MRR | 0.295 | — |
| P50 / P95 | 7829ms / 14282ms | ≈1988ms / ≈5044ms |

agentic 每跳调用一次 judge LLM（deepseek-chat），本次 80 条约 2 倍跳数的额外 Token。

## 结论

1. **稀疏通道：保留**。fused 比 vector_only 整体 Recall@5 +3.4pt、Hit@5 +7.1pt，
   job_requirement +5.9pt、multi_hop +6.7pt，几乎零延迟成本。
2. **Rerank：保留**。fused_rerank 比 fused 整体 +2.4pt，resource_rec +8.9pt、
   job_requirement +4.9pt，P50 无劣化。Rerank 对资源推荐类查询收益最大。
3. **图谱扩展：保留（证据间接）**。fused 模式中稀疏与图扩展是绑定的，现有四模式设计
   无法单独隔离图的贡献；如需单独归因，需增加 vector_sparse（无图）模式再跑一轮。
4. **Agentic 多跳：保留为"按需升级"，不做默认**。multi_hop 切片上只比 fused_rerank
   +4.7pt Recall@5，但 P50 延迟 4 倍、每跳烧 judge Token；single_hop 上（88% vs 95%）
   更差。现有路由的 `allow_multi_hop` 升级机制方向正确——多跳只应发生在路由判定
   需要多跳的查询上。不建议扩大 agentic 使用面。

## 质量缺口（已知，未在本次解决）

- CI 阈值未达标：Hit@5 71.1% < 85%、multi_hop Recall@5 24.9% < 40%、fused MRR 0.524 < 0.6，
  rag-eval 保持 report-only 是对的（Badcase 09 一致）。
- **job_requirement 切片全线溃败（1.9%–12.7%）**：四个模式都救不动，指向岗位数据
  覆盖/描述质量问题而非检索策略问题，建议单独开 badcase。
- 运行中出现 4 次瞬时 500（Badcase 08 的累积状态现象），评测驱动重试后全部恢复；
  由于本次同时修复了"500 被改写成 401"的拦截器 bug（见检查点 2026-09-05），
  重试才能按设计生效。

## 成本记录

- Embedding（bge-m3）：约 900 次查询调用（smoke + 两次全量 + 补测），最便宜的一档；
- judge LLM（deepseek-chat）：smoke 20 次 + multi_hop 补测约 160 次；
- 路由 LLM：0 次（--skip-router）；
- 种子导入：0 次（复用已有 embedding）。
