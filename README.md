# 个人 AI 学习与求职助手

基于多智能体协作、知识图谱和 RAG 融合检索的 AI 学习与求职教练。

## 已实现功能

- JWT 注册、登录与用户数据隔离
- SSE 流式对话、意图路由、多专家协作和结果仲裁
- PostgreSQL/pgvector + Neo4j 图谱融合检索
- 对话摘要、情节记忆、用户画像提取与技能对齐
- PDF、DOCX、TXT 简历解析，PII 脱敏与加密存储
- 学习计划、每日打卡、模拟面试和岗位匹配通知
- 检索、路由、引用质量评估脚本

技术栈：Java 21、Spring Boot 3、LangChain4j、PostgreSQL 16/pgvector、Neo4j 5、React 18、TypeScript、Vite。

## 目录结构

```text
backend/       Spring Boot API 与 Flyway migrations
frontend/      React 单页应用
graph_data/    技能、资源和岗位种子数据
scripts/       数据生成、抽取与导入脚本
evals/         RAG、路由和引用评估集及脚本
docs/          V3/V4 蓝图与核心实现设计
experiments/   可丢弃的技术 spike
```

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

4. 新开终端启动前端：

```powershell
cd frontend
npm install
npm run dev
```

前端开发服务器通过 `/api` 代理到 `8180`。首次使用可以注册账号，也可以在本地启用的开发登录入口快速体验。

容器或反向代理可使用以下无需 JWT 的探针：`GET /healthz` 仅检查进程存活；`GET /readyz` 额外检查 PostgreSQL 与 Neo4j，任一依赖不可用时返回 `503`。

## 验证

```powershell
cd backend
mvn test

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

## 检索评估基线

`evals/rag_testset.json` v1 共 50 条图结构 gold。当前生产策略为图谱融合检索加查询自适应重排：Hit@5 90.0%，Recall@5 65.8%，MRR 0.687。详细调优过程见 `evals/` 与设计文档。

## 后续优先级

1. 增加基于 Testcontainers 的 PostgreSQL/Neo4j 集成测试。
2. 增加登录、对话 SSE、简历上传的前后端端到端测试。
3. 为 LLM 调用补充可观测性、并发上限与后台执行器生命周期管理。
4. 将 DOCX 中稳定的接口和运维约定逐步迁移为可审查的 Markdown 文档。
