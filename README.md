# Tutor Agent

面向学习与求职场景的 AI 教练：将流式对话、受控 RAG 检索、学习计划、简历分析和模拟面试整合为一个可本地运行的 Web 应用。

> 这是一个学习与工程实践项目。模型输出仅供参考，不应替代正式的职业、招聘或教育建议。

## 功能概览

| 能力 | 说明 |
| --- | --- |
| 智能对话 | SSE 流式回复、意图路由、多专家协作、引用校验与可取消请求 |
| 知识检索 | PostgreSQL/pgvector 稠密检索、稀疏检索与 Neo4j 图谱扩展融合 |
| 用户记忆 | 会话滚动摘要、情景记忆、长期记忆同步、画像提取与技能对齐 |
| 求职辅助 | 简历解析和 PII 脱敏、学习计划、岗位匹配、模拟面试与复盘 |
| 运营质量 | 管理端知识库、文档异步索引、审计日志、RAG/路由/引用评测 |

## 技术栈

- 后端：Java 21、Spring Boot 3、LangChain4j、Flyway
- 数据与检索：PostgreSQL 16、pgvector、pg_trgm、Neo4j 5
- 前端：React 18、TypeScript、Vite、Playwright
- 基础设施：Docker Compose；可选阿里云 OSS 与 Mem0

## 快速开始

前置条件：Java 21、Maven 3.9+、Node.js 20+ 和 Docker Desktop。

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d
```

在 `.env` 中填写 LLM 服务密钥；不要提交该文件。然后分别启动后端和前端：

```powershell
# 终端 1：后端
cd backend
mvn spring-boot:run

# 终端 2：前端
cd frontend
npm install
npm run dev
```

前端默认地址是 `http://localhost:5173`，后端 API 默认监听 `http://localhost:8180`。Vite 会将 `/api` 请求代理到后端。

## 项目结构

```text
backend/       Spring Boot API、业务模块、Flyway 数据库迁移和 JUnit 测试
frontend/      React 单页应用与 Playwright 端到端测试
graph_data/    技能、资源和岗位种子数据
evals/         RAG、路由和引用评估数据与脚本
scripts/       数据、备份和运维脚本
docs/          架构、API、运维、评测和安全文档
experiments/   可丢弃的技术验证实验
```

## 开源学习资料

从 [CareerPilot 开源学习营](docs/README.md) 开始。它按“目标 → 代码入口 → 可执行动作 → 观测结果 → 作业验收”组织学习；技术细节再进入架构、评测、运维、安全和 API 参考库。

## 本地启动

前置条件：Java 21、Maven 3.9+、Node.js 20+、Docker Desktop。

1. 复制 `.env.example` 为 `.env`，至少填写 LLM 密钥。简历上传还需要 `RESUME_ENC_KEY`。
2. 启动 PostgreSQL 和 Neo4j：

```powershell
docker compose --env-file .env up -d
```

3. 在 PowerShell 中导入 `.env` 并启动后端：

```powershell
Get-Content ..\.env | Where-Object { $_ -match '^[^#].*=.*$' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item -Path "Env:$name" -Value $value
}
mvn spring-boot:run
```

上述命令在 `backend/` 目录执行。API 默认监听 `http://localhost:8180`，Flyway 会自动建表。

认证使用 HttpOnly Cookie；生产环境启用 `prod` profile 时会强制 Secure Cookie、关闭开发登录和内部评估端点。写请求还需要由前端自动携带 CSRF 双提交令牌。

4. 新开终端启动前端：

```powershell
cd frontend
npm install
npm run dev
```

前端开发服务器通过 `/api` 代理到 `8180`。首次使用可以注册账号，也可以在本地启用的开发登录入口快速体验。

容器或反向代理可使用以下无需 JWT 的探针：`GET /healthz` 仅检查进程存活；`GET /readyz` 额外检查 PostgreSQL 与 Neo4j，任一依赖不可用时返回 `503`。

生产容器可使用 `docker-compose.prod.yml` 启动。该配置不提供敏感变量默认值，并由前端 Nginx 代理 `/api`、注入 CSP 和转发 SSE。启动前需完整填写 `.env` 中的生产密钥。数据库备份可运行：

```powershell
.\scripts\backup_postgres.ps1
```

备份文件写入 `backups/`，应按实际环境再同步到独立、加密的存储。

聊天接口默认按用户限制为每分钟 20 次；生产 profile 默认 10 次。可通过 `CHAT_RATE_LIMIT_PER_MINUTE` 调整；多实例部署时应改用共享的 Redis 限流器。

## 验证

```powershell
cd backend
mvn verify

cd ..\frontend
npm run build
```

当前自动化覆盖核心检索、路由、引用护栏、记忆、画像、匹配、PII 脱敏和认证边界。需要 PostgreSQL、Neo4j 与真实 LLM 的端到端验证尚未纳入默认测试命令。

安装并启动 Docker 后，可显式运行真实 PostgreSQL/pgvector、Neo4j 与 Flyway 集成测试：

```powershell
cd backend
mvn test -DrunIntegrationTests=true -Dtest='*IT'
```

在 WSL 中运行该命令时，确保 WSL 已安装 Java 21、Maven，且当前用户可执行 `docker version`。Maven 测试已自动设置 Docker 29+ 所需的 API 版本。

`.github/workflows/ci.yml` 会在 push 和 pull request 中自动执行后端单元测试、上述 PostgreSQL 集成测试以及前端生产构建。
前端还会执行 Playwright 安全回归；CI 会自动安装 Chromium。

## 材料来源与增量更新

`graph_data/source_overrides.json` 收录经过核验的官方/一手材料链接；导入脚本会优先使用资源自身的 `url`，其次使用该覆盖表。没有经过核验的资源保持无链接，前端会明确显示“未收录原始链接”。

对于已经导入的知识库，不必重新生成 embedding 或清空 `kg_chunks`。先生成增量 SQL，再在项目 PostgreSQL 容器中执行：

```powershell
node scripts/generate_source_updates.mjs > source_updates.sql
wsl docker exec -i tutor-postgres psql -U tutor -d tutor < source_updates.sql
```

该脚本只更新 `source_url`、`source_title` 与 `retrieved_at`，并使用单个事务提交。

## 生产配置

使用 `prod` profile 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
mvn spring-boot:run
```

生产 profile 会关闭 `/auth/dev-login`，并要求显式提供：

- `POSTGRES_PASSWORD`
- `NEO4J_PASSWORD`
- `JWT_SECRET`（至少 32 字符的随机值）
- `RESUME_ENC_KEY`
- `DEEPSEEK_API_KEY`
- `SILICONFLOW_API_KEY`

生产 profile 同时会关闭 `/auth/dev-login` 与 `/internal/*` 评估端点。不要在生产环境使用仓库中的开发默认密码。建议在反向代理层启用 HTTPS、限流、请求体限制和安全日志脱敏。

Neo4j 查询具有独立的运行时降级策略：单次查询默认超时 2 秒，连续 3 次失败后熔断 30 秒；图谱扩展、技能对齐和速成技能查询在熔断期间返回安全空结果，主流程继续使用 PostgreSQL/向量检索结果。`/readyz` 仍会在 Neo4j 不可用时返回 `503`，不会把未就绪实例当作健康实例。可通过 `NEO4J_QUERY_TIMEOUT_SECONDS`、`NEO4J_FAILURE_THRESHOLD`、`NEO4J_OPEN_SECONDS` 调整。

## 检索评估基线

`evals/rag_testset.json` v1 共 280 条中文 Golden Set：单跳技能 60 条、资源推荐 80 条、岗位要求 60 条、多跳前置 80 条。每条用例以人工确认的 Gold 节点作为确定性评分依据；评测使用当前真实检索管线，不复制线上逻辑。

开发环境可通过前端 `RAG 评测` 页面启动真实评测。页面调用后端现有 `vector_only`、`fused`、`fused_rerank` 和 `agentic` 四条检索管线，结果写入 `eval_runs`，展示运行历史、总体指标、分类切片、严格历史基线对比和失败用例。评测同时输出：

- Hit@K 的 Wilson 95% 置信区间，避免把小样本波动当成能力提升；
- P0 执行可靠性与 P1 检索质量门禁。只有完整 Golden Set 通过门禁才具备发布资格，采样运行仅用于诊断；
- 确定性 Badcase 根因聚类（如多跳覆盖不足、岗位技能覆盖不足、资源类型错配）以及对应 Owner 和修复建议。

启动前设置 `INTERNAL_ENDPOINTS_ENABLED=true`，并确保 PostgreSQL、Neo4j 和 LLM/Embedding 服务可用；评测集路径可通过 `RAG_EVAL_DATASET_PATH` 覆盖，门禁阈值可通过 `RAG_EVAL_MIN_OVERALL_HIT`、`RAG_EVAL_MIN_OVERALL_RECALL`、`RAG_EVAL_MIN_MULTI_HOP_HIT` 和 `RAG_EVAL_MAX_ERRORS` 调整。

当前 Golden Set 是项目领域回归集，Gold 节点由知识图谱关系与人工确认共同确定。要形成真实用户 RAG 报告，应将脱敏真实查询、人工确认的相关节点和参考证据加入独立数据集，再在相同知识库快照和模型配置下进行基线对比。

## 管理端

管理端入口为 `/admin`，只对数据库中 `role = 'ADMIN'` 且未被禁用/软删除的账号开放。当前包含用户状态管理、RAG 评测运行概览、知识库文档中心和操作审计；用户删除采用可恢复的软删除，不直接删除用户及其外键关联数据。

首次使用时，先完成普通账号注册，再由数据库管理员一次性授予管理员角色（不要把该 SQL 暴露给前端）：

```bash
docker exec tutor-postgres psql -U tutor -d tutor \
  -c "UPDATE users SET role = 'ADMIN' WHERE lower(email) = lower('admin@example.com');"
```

后端启动时 Flyway 会自动应用 `V16__admin_console.sql`，创建角色、软删除字段和 `admin_audit_log` 审计表。管理员状态变更接口仍会在服务端重新查询角色，不依赖前端菜单或旧 JWT。

知识库原文件存储在私有阿里云 OSS Bucket，环境变量见 `.env.example` 中的 `OSS_*` 配置。管理员可在 `/admin/documents` 上传 PDF、DOCX、TXT、Markdown；系统将异步解析、切分、向量化，并在状态为 `indexed` 后合并进现有 RAG 检索。PostgreSQL 保存文档元数据、处理状态、分块和 embedding，不保存原始文件；软删除时会同步清理 OSS 对象和检索分块。

## 后续优先级

1. 增加登录、对话 SSE、简历上传的前后端端到端测试。
2. 增加 Neo4j/检索链路的运行指标与告警面板。
3. 为 LLM 调用补充可观测性、并发上限与后台执行器生命周期管理。
4. 将 DOCX 中稳定的接口和运维约定逐步迁移为可审查的 Markdown 文档。
