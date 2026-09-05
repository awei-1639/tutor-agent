# 项目结构与模块边界

## 1. 顶层结构

```text
agent/
├─ backend/                 Spring Boot 后端与 Flyway 迁移
├─ frontend/                React/Vite 前端
├─ evals/                   评测集、评测执行器和评测结果
├─ graph_data/              可导入的技能、资源、岗位和来源数据
├─ scripts/                 数据生成、导入、启动和运维脚本
├─ docs/                    架构、运行、评测、安全和贡献资料
├─ experiments/             一次性技术 Spike，不被正式代码依赖
├─ docker-compose.yml       本地依赖服务
├─ docker-compose.prod.yml  生产容器编排
└─ .env.example             配置模板，不放真实密钥
```

顶层目录按“可部署单元、可复现实验、输入数据、工具脚本、说明资料”划分。数据和评测集不放进 `backend/src`，避免把测试输入误当成应用资源；部署配置也不放进业务源码。

## 2. 后端模块边界

后端采用按业务能力组织的模块，而不是把所有 Controller、Service、Repository 分别集中到三个大目录：

```text
com.tutor/
├─ auth          登录、Cookie、JWT、刷新令牌、CSRF
├─ chat          对话编排、SSE、会话、限流、留痕
├─ context       Prompt 上下文和 Token 预算
├─ contract      跨模块的稳定数据契约和枚举
├─ expert        意图路由、专家执行、结果仲裁
├─ retrieval     向量、稀疏、图谱、融合和多跳检索
│  ├─ agentic     Agentic 多跳检索
│  ├─ fusion      RRF、候选融合和 Rerank
│  ├─ vector      pgvector / pg_trgm
│  ├─ graph       Neo4j 图扩展
│  └─ resilience  Neo4j 超时和熔断
├─ memory        Episode、Mem0、授权和记忆降级
│  ├─ local       会话和本地情节记忆
│  ├─ application 本地/远端记忆编排
│  ├─ external    Mem0 客户端和熔断
│  └─ policy      授权、删除和记忆策略
├─ profile       用户画像和技能对齐
├─ resume        简历解析、PII 脱敏和加密存储
├─ plan          学习计划和异步计划任务
├─ push          岗位匹配、能力缺口和通知
├─ interview     模拟面试
├─ knowledge     管理端知识文档与 OSS
├─ eval          RAG 评测和质量门禁
├─ admin         管理员概览、用户操作和审计
├─ llm           模型网关、预算和并发闸门
├─ guard         引用和输出护栏
└─ config        Spring 配置、健康检查和基础设施装配
```

当前已落地的职责边界示例：

```text
chat/internal  ──> eval/InternalMemorySeedService
push            ──> NotificationStore
memory/external ──> MemorySyncJobStore
memory/local    ──> FactPersistenceStore + FactPolicy
interview       ──> *JobStore + *Worker + application facade
```

### 放置规则

- 只服务一个业务能力的类，放在对应模块；
- 被多个模块共享且属于稳定协议的类型，放入 `contract`；
- 第三方客户端和熔断/超时封装，放入实际使用它的模块，不创建一个无边界的 `utils` 包；
- 数据库 SQL 和 Flyway 迁移放在 `backend/src/main/resources/db/migration`；
- 新增跨模块依赖前，先判断是否应提取为 `contract` 或应用服务接口；
- 不在 Controller 中直接拼接 SQL、调用 LLM 或执行图查询；
- 不把评测专用规则复制进线上检索器，评测必须调用真实管线。

## 3. 前端边界

```text
frontend/src/
├─ pages/       路由页面和页面级查询/交互
├─ components/  跨页面 UI 组件
├─ lib/         API 客户端、Markdown 安全渲染等无页面状态能力
├─ styles/      全局样式和设计令牌
├─ App.tsx      路由和页面装配
└─ main.tsx     应用入口
```

页面不应直接拼接后端 URL 或重复实现 SSE 解析；统一放在 `src/lib/api.ts`。跨页面复用的视觉和交互才进入 `components`，避免过早抽象。

## 4. 数据、脚本和评测边界

| 类型 | 放置位置 | 规则 |
| --- | --- | --- |
| 输入种子数据 | `graph_data/` | JSON 可审查、来源可追溯 |
| 一次性生成/导入 | `scripts/` | 明确输入、输出和副作用 |
| 质量回归 | `evals/` | Gold、指标、基线和结果分离 |
| 实验验证 | `experiments/` | 不被正式代码引用，结论写回文档 |
| 设计和运维 | `docs/` | 以 Markdown 为主，命令与代码同步 |

脚本生成的临时 SQL、Cypher、日志和评测结果不应默认提交。需要提交的结果必须带数据集版本、代码版本和运行配置。

## 5. 变更决策

### 可以直接新增

- 同一业务模块内的类、测试和文档；
- 新的评测用例和明确标注的 Gold；
- 新的 Flyway 迁移；
- 与现有启动路径兼容的运维脚本。

### 需要先补设计说明

- 修改公共 SSE 事件；
- 跨模块引入新的依赖方向；
- 移动 `graph_data`、`evals` 或 `scripts` 路径；
- 改变 embedding 维度或向量表结构；
- 改变认证 Cookie、权限或外部记忆数据边界。
