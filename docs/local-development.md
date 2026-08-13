# 本地开发与复现

## 1. 前置条件

- Java 21；
- Maven 3.9+；
- Node.js 20+；
- Docker Desktop，或 WSL 中可用的 Docker；
- 可访问的 LLM 和 Embedding API。只运行单元测试时不需要真实 API Key。

## 2. 配置环境变量

在项目根目录执行：

```powershell
Copy-Item .env.example .env
```

至少配置：

```text
POSTGRES_PASSWORD
NEO4J_PASSWORD
JWT_SECRET
RESUME_ENC_KEY
DEEPSEEK_API_KEY
SILICONFLOW_API_KEY
```

本地可将 `DEV_LOGIN_ENABLED=true` 和 `AUTH_COOKIE_SECURE=false` 保持为开发配置。生产环境必须使用 `prod` profile，并关闭开发登录。

Mem0 和 OSS 都是可选能力：`MEM0_ENABLED=false` 时只使用本地 Episode 记忆；`OSS_ENABLED=false` 时不要使用管理员知识库文档上传。

## 3. 启动依赖服务

在项目根目录执行：

```powershell
docker compose --env-file .env up -d postgres neo4j
docker compose ps
```

确认：

```powershell
Invoke-WebRequest http://localhost:7474 -UseBasicParsing
Test-NetConnection localhost -Port 5432
Test-NetConnection localhost -Port 7687
```

数据库账号默认是 `tutor`，数据库名是 `tutor`。Flyway 会在后端启动时自动应用 `backend/src/main/resources/db/migration` 下的迁移。

## 4. 启动后端

在 `backend/` 目录执行。PowerShell 中需要先把根目录 `.env` 导入当前进程：

```powershell
Get-Content ..\.env | Where-Object { $_ -match '^[^#].*=.*$' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item -Path "Env:$name" -Value $value
}
$env:INTERNAL_ENDPOINTS_ENABLED = 'true'
mvn spring-boot:run
```

默认地址：`http://localhost:8180`。

健康检查：

```powershell
Invoke-WebRequest http://localhost:8180/healthz -UseBasicParsing
Invoke-WebRequest http://localhost:8180/readyz -UseBasicParsing
```

`healthz` 只表示进程存活；`readyz` 还会检查 PostgreSQL 和 Neo4j，任一依赖不可用时返回 `503`。

WSL 也可以使用仓库脚本启动已经打包的后端：

```bash
bash scripts/start_backend_wsl.sh
```

该脚本假设项目位于 `/mnt/d/git/git_repo03/agent`，并会优先启动 `tutor-postgres` 和 `tutor-neo4j`。

## 5. 启动前端

新开终端：

```powershell
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会将 `/api` 代理到 `http://localhost:8180`。

## 6. 初始化种子数据

项目提供技能、资源和岗位种子数据。生成脚本会读取 `graph_data/*.json`，调用 Embedding API，并生成 Neo4j Cypher 和 PostgreSQL SQL 文件：

```powershell
cd <项目根目录>
node scripts/import_seed.mjs
```

脚本会打印 `OUT_DIR`。将输出目录中的文件导入对应容器，示例：

```bash
wsl docker exec -i tutor-neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD" < seed.cypher
wsl docker exec -i tutor-postgres psql -U tutor -d tutor -q < kg_chunks.sql
wsl docker exec -i tutor-postgres psql -U tutor -d tutor -q < jobs.sql
```

如果使用 PowerShell 直接执行导入，请根据脚本打印的实际路径替换文件名，不要假定文件一定生成在项目根目录。

## 7. 验证代码

```powershell
cd backend
mvn verify

cd ..\frontend
npm run build
```

依赖 Docker 的集成测试：

```powershell
cd backend
mvn test -DrunIntegrationTests=true -Dtest='*IT'
```

前端安全回归：

```powershell
cd frontend
npm run e2e
```

## 8. 常见问题

### 后端启动时报数据库连接失败

先检查容器和端口：

```powershell
docker compose ps
Test-NetConnection localhost -Port 5432
Test-NetConnection localhost -Port 7687
```

确认后端使用的是宿主机地址 `localhost`。只有在 `docker-compose.prod.yml` 的 backend 容器内部，数据库地址才是服务名 `postgres` 和 `neo4j`。

### `/readyz` 返回 503，但聊天仍可能可用

这是设计上的区别：`/readyz` 是部署就绪探针，要求完整依赖；请求级检索拥有 Neo4j 熔断降级，见 [运维手册](operations.md)。

### 评测页面没有数据

确认后端设置 `INTERNAL_ENDPOINTS_ENABLED=true`，评测集文件存在，且 PostgreSQL、Neo4j 和 Embedding 服务可用。
