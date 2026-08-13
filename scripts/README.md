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

| 脚本 | 作用 | 副作用 |
| --- | --- | --- |
| `gen_seed.mjs` | 调用模型生成技能/资源/岗位种子 | 写入 `graph_data/` |
| `import_seed.mjs` | 生成 Neo4j Cypher 和 PostgreSQL SQL | 调用 Embedding API，输出临时目录 |
| `extract_triples.py` | 从 staging triples 导入允许的图关系 | 写入 Neo4j |
| `generate_source_updates.mjs` | 生成来源增量 SQL | 输出 SQL，不直接写库 |
| `start_backend_wsl.sh` | WSL 启动本地依赖和已打包后端 | 启动容器和 Java 进程 |
| `live_eval_smoke.sh` | 启动后端并运行评测烟雾测试 | 启动进程、写临时日志 |
| `backup_postgres.ps1` | 备份 PostgreSQL | 写入 `backups/` |

运行前应先阅读脚本头部的前置条件和输出路径。涉及数据库写入的脚本不得默认指向生产环境。
