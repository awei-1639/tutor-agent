# Scripts

脚本按副作用分为数据处理、开发启动、评测辅助和运维备份。脚本不应被后端运行时直接依赖，正式业务能力必须落在 `backend` 中。

## 验证入口

在仓库根目录运行以下命令，可执行当前默认的后端单元测试与前端生产构建：

```powershell
.\scripts\verify.ps1
```

如需只验证一侧：

```powershell
.\scripts\verify.ps1 -SkipFrontend
.\scripts\verify.ps1 -SkipBackend
```

## 脚本索引

### 数据与图谱

| 脚本 | 作用 | 副作用 |
| --- | --- | --- |
| `gen_seed.mjs` | 调用模型生成技能/资源/岗位种子 | 写入 `graph_data/` |
| `import_seed.mjs` | 生成 Neo4j Cypher 和 PostgreSQL SQL | 调用 Embedding API，输出临时目录 |
| `extract_triples.py` | 从 staging triples 导入允许的图关系 | 写入 Neo4j |
| `import_triples.py` | staging_triples(approved) → Neo4j 边 | 写入 Neo4j |
| `fetch_real_jobs.py` | 拉取 HN "Who is hiring" 真实岗位入 jobs 表 | 调用外部 HTTP，写库 |
| `generate_source_updates.mjs` | 生成来源增量 SQL | 输出 SQL，不直接写库 |
| `patch_aliases.cypher` | 实体别名补丁 | 写入 Neo4j |

### 评测

| 脚本 | 作用 | 副作用 |
| --- | --- | --- |
| `eval-local.sh` | 一键评测：起依赖 → 起后端 → (幂等)导种子 → 跑评测 → 基线对比 | 启动进程，调 Embedding/LLM API |
| `live_eval_smoke.sh` | 启动后端并运行评测烟雾测试 | 启动进程、写临时日志 |
| `eval-agentic-multihop.sh` | Agentic 模式在 multi_hop_prereq 切片的专项评测 | 调用 LLM API |
| `memory-eval-wsl.sh` | 记忆评测一键脚本（单 WSL 会话内完成起库到评测） | 启动进程，调 Embedding API |
| `check-router-accuracy.sh` | 路由准确率探针（30 条标注集） | 调用 LLM API |
| `diag-job-requirement.sh` | job_requirement 切片单条探针（Badcase 10 诊断） | 调用 Embedding API |
| `check-embedding-determinism.mjs` | Embedding 结果抖动检测 | 调用 Embedding API |
| `collect-routing-calibration.mjs` / `fit-routing-calibration.mjs` | 路由校准数据收集与拟合 | 写入模型文件 |
| `wait-backend.sh` | 等待 `/readyz` 就绪 | 无 |

### 运维与验证

| 脚本 | 作用 | 副作用 |
| --- | --- | --- |
| `verify.ps1` | 后端测试 + 前端构建总验证 | 启动进程 |
| `verify-interview-integration-wsl.sh` | WSL 内运行面试 Testcontainers 集成测试 | 启动容器 |
| `check-flyway-migration-immutability.sh` | 校验历史 Flyway migration 未被修改 | 无 |
| `verify-containers.sh` | 本地容器演练：依赖健康、后端探针、前端反代 | 启动容器 |
| `start_backend_wsl.sh` | WSL 启动本地依赖和已打包后端 | 启动容器和 Java 进程 |
| `llm-cost-report.mjs` | LLM 消耗报表（按用途/模型汇总 token） | 只读 |
| `cleanup-observability-data.mjs` | 观测数据保留清理（默认 dry-run） | `--apply` 时删库 |
| `backup_postgres.ps1` | 备份 PostgreSQL | 写入 `backups/` |

运行前应先阅读脚本头部的前置条件和输出路径。涉及数据库写入的脚本不得默认指向生产环境。
