# 个人AI学习与求职助手 (personal-ai-tutor)

基于多智能体协作、知识图谱与 RAG 融合检索的 AI 学习与求职教练。

**技术栈（Java 主线）**：Spring Boot 3 + LangChain4j + Neo4j + PostgreSQL(pgvector) + DeepSeek API

## 目录结构

```
agent/
├── docs/            # 设计文档（V3 蓝图 / V4 路线图 / 双版实现设计）
│   └── _generator/  # 文档生成脚本（docx，双版同步维护）
├── backend/         # Spring Boot 3 主服务
├── graph_data/      # 图谱种子数据（技能/资源/岗位）
├── evals/           # 评估集与评估脚本
├── scripts/         # 离线脚本（种子数据抽取等，允许 Python）
├── experiments/     # 丢弃式 spike（验证后删除，不混入主干）
└── docker-compose.yml
```

## 快速开始

```bash
# 1. 配置密钥
cp .env.example .env   # 填入 DEEPSEEK_API_KEY / SILICONFLOW_API_KEY

# 2. 启动存储（需要 Docker）
docker compose up -d

# 3. 启动服务（Flyway 自动建表）
# 注意: Spring Boot 不读 .env 文件, 必须先注入环境变量 (Git Bash):
cd backend && set -a && source ../.env && set +a && mvn spring-boot:run
```

## 开工前必读

- 范围与验收：`docs/个人AI学习与求职助手_最佳设计方案_V3.docx`（Phase 1 = 全部）
- 写法与契约：`docs/核心实现设计_记忆机制与Context工程_Java版.docx`（先看 1.1 阶段映射表）
- 节奏控制：`docs/个人AI学习与求职助手_演进设计方案_V4无时限版.docx`（退出标准）

## 评估结果（evals/rag_testset.json v1，50 条图结构 gold，2026-07-26）

生产管线 = 图谱融合 + **查询自适应重排**（"找资源"类查询走 bge-reranker，其余保持纯融合）：

| 指标 | vector_only | fused | fused+自适应rerank（生产） |
|------|------------|-------|---------------------------|
| Hit@5 | 68.0% | 88.0% | **90.0%（+22pp）** |
| Recall@5 | 56.3% | 60.7% | **65.8%（+9.5pp）** |
| MRR | 0.569 | 0.602 | **0.687（+0.118）** |
| 检索 P50 / P95 | 145 / 227ms | 156 / 194ms | 164 / 482ms |

切片（Recall@5，vs 纯向量基线）：single_hop 100%（=）；resource_rec **83.6%（+3pp）**；job_requirement **35%（0→35）**；multi_hop **18.5%（10.7→18.5）**——**无任何切片低于基线**。路由准确率 96.7%（29/30）。

调优过程（6 轮评估驱动，详见 evals/results/）：初版 fused 反而拖累 Recall → 逐轮诊断出 RRF 求和的枢纽接管、K=60 分差拉平使扩展节点数学上无法进 top5、扩展缺每源配额、α 阈值差一毫米、reranker 会降权图扩展节点（技能 chunk"看不出"在回答岗位问题）五个问题 → 最终落地查询自适应管线。**每一步变更都有评估数字背书。**

## 当前状态

- [x] 设计定稿（两轮自查 + 双代理独立审查，19 处问题清零）
- [x] 项目骨架：compose / Flyway V1 全量 schema / 契约类
- [ ] Spike×3（experiments/，见其 README）
- [ ] 种子数据管线（scripts/ → graph_data/ → 导入）
- [ ] 最小对话链路（检索 → 单 Agent 回答带引用）
