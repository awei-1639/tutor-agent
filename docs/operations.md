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
| 记忆同步 worker 崩溃 | 租约到期后由其他实例重新认领 | 云端同步可能延迟但不会永久卡死 | 检查 `MEMORY_SYNC_LEASE_SECONDS` 和 Outbox 堆积 |
| PostgreSQL 不可用 | 业务请求失败，`/readyz` 为 503 | 需要恢复数据库 | 检查容器、磁盘、连接数和迁移 |

### 文档上传吞吐参数

默认单文件上限为 50 MB、单请求上限为 55 MB。管理端先创建一个 24 小时上传会话，浏览器直接把文件上传到 OSS；超过 8 MB 的文件会按 8 MB 分片并行上传（最多 4 片同时传），再由 API 校验并合并对象。会话 ID 只以文件指纹映射保存在浏览器 localStorage，页面刷新或切换页面后会重新获取会话并查询 OSS 已完成分片，只补传缺失部分；OSS 签名 URL 只有 15 分钟有效，恢复时会自动重新签发，上传过程中遇到签名过期也会自动刷新。这样大文件不会经过 API 服务的单次 multipart body。上传完成后仍会在后台做文件签名和恶意文件检查。后台队列达到 1000 个活动任务时返回 503，避免继续堆积。摄取 worker 默认每实例最多并行 4 个文档，每个文档最多并行 8 个 Embedding 请求。可按机器内存、OCR/Embedding 配额调整：

```text
KNOWLEDGE_UPLOAD_MAX_FILE_SIZE=50MB
KNOWLEDGE_UPLOAD_MAX_REQUEST_SIZE=55MB
KNOWLEDGE_MULTIPART_THRESHOLD=8MB
KNOWLEDGE_MULTIPART_PART_SIZE=8MB
KNOWLEDGE_UPLOAD_SESSION_TTL=24h
KNOWLEDGE_INGESTION_MAX_IN_FLIGHT=4
KNOWLEDGE_EMBEDDING_CONCURRENCY=8
KNOWLEDGE_INGESTION_MAX_PENDING_JOBS=1000
```

路由只有在校准后的越界概率达到 `ROUTING_OUT_OF_SCOPE_THRESHOLD`（默认 0.92）时才会跳过知识检索；LLM 返回的原始 `confidence` 仅用于观测，不直接作为安全门槛。校准器通过 `ROUTING_CALIBRATION_ENABLED` 和 `ROUTING_CALIBRATION_MODEL_PATH` 配置，未启用、模型缺失或模型损坏时 `calibratedConfidence` 保持为空并走安全回退。路由故障或灰区请求走 `CHAT + SINGLE`，避免领域问题被误判后直接丢弃。

每轮的 `turn_traces.snapshot` 会记录路由 scope、原始/生效意图、置信度、检索建议、是否跳过检索，以及实际检索跳数和停止原因。可据此统计路由降级率、无必要多跳率和 Judge 失败率；snapshot 只保存枚举、数值和原因码，不保存原始问题文本。

扩大文件上限时必须同步修改反向代理 `client_max_body_size`、WAF/网关 body limit，并确认容器内存；当前上传实现仍会将单个文件读入 JVM 内存，超大文件不应直接提高到数百 MB。多实例部署时，worker 并发和上传限流仍是“每实例”保护，需要在网关/Redis 层增加集群级令牌桶和并发配额。

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

流式调用显式请求 `stream_options.include_usage`，流末尾以供应商上报的真实用量结算（并驱动预留估算的校准因子，EMA 钳制 0.8~1.5）；`finish_reason=length` 会以 `done.truncated=true` 透出，前端提供"继续生成"入口。

### 2.1 预算分层（V67 迁移起）

| 层 | 默认上限 | 拒绝时错误码 | 说明 |
| --- | --- | --- | --- |
| 单轮 | 50,000 / trace | `budget_turn` | 同一 trace 的所有调用累计，防失控循环 |
| 用户日 | `LLM_USER_DAILY_TOKEN_LIMIT`（300,000） | `budget_user_daily` | 回合开始时归属 trace 到用户后生效；耗尽在回合入口快速失败 |
| 全局日 | 2,000,000 | `budget_global` | 全部用途共享的成本硬顶 |
| 后台份额 | 全局日的 20%（`LLM_BACKGROUND_SHARE_PERCENT`） | `budget_background` | 摘要/抽取/批量嵌入专用；顺延由 outbox/调度自动重试 |

归属模型：`ChatService`/`InterviewTurnService`/`PlanService` 在回合开始调用 `attributeTrace(traceId, userId)`，之后该 trace 内所有网关调用（含后台任务）计入该用户；评测与知识入库等未归属 trace 只受全局与后台层约束。

预算压力阶梯（`BudgetPressureService`，≤30s 缓存）：全局日预算使用 ≥80% 关闭多跳升级、专家扇出封顶 1、聊天输出上限收紧到 1000；≥95% 后台任务直接顺延、证据保底减半；查询失败按 NORMAL 处理，硬上限始终由原子预留保证。

运维要点：

- 记账表 `llm_turn_budget`（保留 3 天）、`llm_daily_budget`、`llm_user_budget`（保留 30 天），每日 03:10 由 `purgeBudgetRows` 清理。
- 水位查询：`SELECT reserved_tokens + actual_tokens FROM llm_daily_budget WHERE budget_day = CURRENT_DATE`。
- 各层拒绝会抛 `BudgetExhausted` 并在 SSE `error` 事件携带对应 `code`；同时计入指标 `tutor.llm.budget.rejected{kind=turn|user_daily|global|background_deferred}`，水位仪表为 `tutor.llm.budget.daily.used.percent`，按 kind 建阈值告警即可覆盖各层拒绝。
- 预算压力降级会以 `BUDGET_MULTI_HOP_DISABLED` reason 与 `turn_traces` 的 `budget_shed` 字段留痕，排查"这轮为什么没多跳"先看这里。

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

### Episode 密钥轮换（仅情景记忆）

1. 生成新密钥，设置 `RESUME_ENC_KEY`、递增的 `RESUME_ENC_KEY_ID`，并暂时保留旧值到 `RESUME_ENC_PREVIOUS_KEY` 与 `RESUME_ENC_PREVIOUS_KEY_ID`。此回填只处理 Episode，不覆盖简历、PII 映射和会话消息/摘要。
2. 发布并观察 `episode privacy backfill updated=...` 日志。轮换期间新写入使用当前密钥，旧 Episode 仍可用旧密钥读取。
3. 通过数据库只读检查确认没有旧版本记录：`SELECT count(*) FROM episodes WHERE summary_encryption_key_id = '<旧版本>';`。确认备份可恢复后，删除旧密钥配置并重启。
4. 若旧密钥不可用，系统不会覆盖不可解密的密文；对应记录只返回脱敏投影，需要先恢复旧密钥再继续回填。

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

记忆同步租约默认 300 秒，可通过 `MEMORY_SYNC_LEASE_SECONDS` 调整。租约过期后旧 worker 的完成/失败回调会因 fencing token 失效而被忽略；Mem0 写入使用幂等键，删除操作本身也应保持幂等。

手动调用 `/memories/remote-deletion/retry` 按用户限流，默认每分钟最多 3 次，可通过 `MEMORY_SYNC_RETRY_LIMIT_PER_MINUTE` 调整；生产环境限流窗口存放在 PostgreSQL，多个 API 实例共享额度。超过限制或限流表不可用时返回 429，避免把 Mem0 列表发现和删除接口变成可反复放大的外部请求。

语义事实层配置：`MEMORY_FACTS_ENABLED`（kill-switch，默认开启）、`MEMORY_FACTS_MAX_PER_EXTRACTION`（单次抽取事实上限，默认 8）、
`MEMORY_FACTS_MAX_ACTIVE_PER_USER`（每用户有效事实上限，默认 60）、`MEMORY_FACTS_RECALL_TOP_K`（单轮召回条数，默认 6）、
`MEMORY_RECALL_RECENCY_DECAY_DAYS`（记忆排序的 recency 衰减半衰参数 τ，默认 30 天）。关闭事实层后抽取与召回同时停用，聊天不受影响。

同步 Outbox 暴露 `tutor.memory.sync.backlog`（待处理、退避中和处理中任务数）与 `tutor.memory.sync.failed`（终态失败任务数）两个低基数指标，默认每 10 秒刷新，可通过 `MEMORY_SYNC_METRICS_REFRESH_MS` 调整。生产环境应对失败数和持续增长的积压配置告警。

OSS Bucket 还必须配置 CORS，允许前端站点对签名 URL 发起 `PUT`，至少放行 `Content-Type` 请求头并暴露 `ETag` 响应头；不要把 AccessKey 或 STS 长期凭证下发到浏览器。签名会在 15 分钟后失效，未完成的普通/分片会话由后台清理；同时建议配置 OSS 生命周期规则，自动终止长期未完成的 Multipart Upload，覆盖应用进程在创建会话后立即崩溃的极端情况。
