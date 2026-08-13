# 运维与故障处理

## 1. 服务状态

| 检查项 | 地址/命令 | 说明 |
| --- | --- | --- |
| 进程存活 | `GET /healthz` | 只检查应用进程 |
| 完整就绪 | `GET /readyz` | 检查 PostgreSQL 和 Neo4j |
| Actuator | `GET /actuator/health` | Spring Boot 健康信息 |
| 指标 | `GET /actuator/prometheus` | Prometheus 格式，部署时需限制访问 |
| Docker 状态 | `docker compose ps` | 查看容器状态和健康检查 |

## 2. 故障降级矩阵

| 故障 | 主链路行为 | 用户侧表现 | 恢复方式 |
| --- | --- | --- | --- |
| Neo4j 查询超时 | 图扩展返回安全空结果，保留 PostgreSQL 召回 | 普通问答继续；路径完整性可能下降 | 熔断冷却后半开探测 |
| Neo4j 连续失败 | 进入 Open，短时间内不再访问 | 不展示底层堆栈，标记能力受限 | 检查 Bolt、密码、内存和数据 |
| Mem0 超时 | 使用本地 Episode | 主对话继续 | 检查授权、URL、API Key |
| Rerank 失败 | 使用混合检索排序 | 资源推荐仍返回，但排序可能退化 | 检查模型配置和配额 |
| LLM 失败 | 按节点回退、重试或直答 | 显示可理解的失败/降级提示 | 检查模型、网络和预算 |
| OSS 上传失败 | 不写入成功文档状态 | 提示检查 OSS 配置和权限 | 检查 Bucket、Endpoint、权限 |
| 文档解析失败 | 状态置为 `failed` | 管理员可查看错误并重试 | 修正文档或解析问题后重试 |
| PostgreSQL 不可用 | 业务请求失败，`/readyz` 为 503 | 需要恢复数据库 | 检查容器、磁盘、连接数和迁移 |

### LLM 节点明细

所有模型调用必须经过 `LlmGateway`。路由失败或返回非法意图时降为 `CHAT`，避免故障时扇出专家；专家全部失败时不再启动一次更贵的直答，而是返回明确不可用；专家部分成功时仍交给仲裁，仲裁失败则输出已完成专家结果的确定性摘要。向量失败降为稀疏检索，重排失败保留融合排序，多跳 judge 失败或非法 JSON 停止继续扩展。

| purpose | 输入上限 | 输出上限 | 显式尝试 |
| --- | ---: | ---: | ---: |
| router | 1,200 | 96 | 1 |
| chat | 8,000 | 1,600 | 2 |
| expert | 5,000 | 1,800 | 1 |
| summary | 5,000 | 600 | 1 |
| extract | 8,000 | 1,600 | 2 |
| judge | 3,500 | 600 | 1 |
| plan | 6,000 | 1,800 | 2 |
| embed / rerank | 8,000 / 6,000 | — | 2 / 1 |

SDK 内置重试已关闭，由网关统一控制。每次尝试先预留估算 token；失败尝试按保守估算计入结算，供应商未返回 usage 时也不会按零成本释放。每日限额、单轮限额和实例并发闸门均在外呼前生效；SSE 断开会取消供应商流并结算已产生的部分输出。`TokenBudget` 截断结果不会超过目标 token 上限。

## 3. Neo4j 熔断参数

```text
NEO4J_QUERY_TIMEOUT_SECONDS=2
NEO4J_FAILURE_THRESHOLD=3
NEO4J_OPEN_SECONDS=30
```

默认含义：单次查询 2 秒超时，连续 3 次失败后熔断 30 秒。熔断只保护请求级查询，不会让 `/readyz` 虚报完整就绪。

## 4. 容器与日志排查

```powershell
docker compose ps
docker logs --tail 200 tutor-postgres
docker logs --tail 200 tutor-neo4j
```

后端优先观察 Flyway 迁移、数据库连接、LLM HTTP 状态和超时、`traceId` 对应的检索/专家耗时，以及熔断打开和恢复日志。

不要在日志中打印 Token、Cookie、API Key、完整简历或完整外部记忆内容。

每个 HTTP 响应都会返回 `X-Request-Id`。客户端可以在排障请求中携带由字母、数字、`_`、`-` 构成且长度为 8 到 64 的同名值；其他输入会由服务端替换为新的随机 Trace ID。日志中使用 `traceId` MDC 字段关联同一请求。

## 5. 备份与恢复

PostgreSQL 备份脚本：

```powershell
.\scripts\backup_postgres.ps1
```

脚本输出到 `backups/`。生产环境还需要将备份同步到独立、加密并经过恢复演练的存储。OSS 原文件和 Neo4j 数据卷也要分别制定备份策略。

恢复后至少运行 `/readyz`、数据库集成测试和一轮 RAG 烟雾评测，并确认 OSS 对象与 PostgreSQL 文档元数据一致。

## 6. 部署注意事项

```powershell
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
```

生产 profile 会关闭开发登录和 `/internal/*`。必须显式设置数据库密码、JWT 密钥、简历加密密钥和模型 API Key。

本地评测需要显式设置 `INTERNAL_ENDPOINTS_ENABLED=true`；即使启用，`/internal/*` 默认也只接受回环地址请求。不要通过反向代理将它暴露给远程客户端。

多实例部署前需要重新设计共享聊天限流、后台任务执行器、熔断状态、评测任务并发以及 Trace/指标聚合。
