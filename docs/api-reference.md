# API 参考

接口以当前 Controller 实现为准。本页提供开源学习者需要的入口索引，完整请求体和响应结构请结合源码中的 record/DTO 阅读。

## 1. 通用约定

- 默认后端地址：`http://localhost:8180`；
- 前端开发环境通过 `/api` 代理；
- Cookie 认证由服务端设置；
- 已认证写请求需要 `X-CSRF-Token`，认证入口和内部评测端点除外；
- 用户接口由当前登录用户隔离；
- 管理接口需要管理员角色；
- 错误响应应优先展示 `message` 或 `error`，不要把 Java 堆栈返回给用户。

## 2. 认证与健康检查

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/auth/register` | 注册 |
| POST | `/auth/login` | 登录并设置 Cookie |
| POST | `/auth/dev-login` | 本地开发登录，生产关闭 |
| POST | `/auth/refresh` | 刷新并轮换 Token |
| POST | `/auth/logout` | 退出 |
| GET | `/healthz` | 存活探针 |
| GET | `/readyz` | 依赖就绪探针 |

## 3. 用户主流程

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/chat` | SSE 流式对话 |
| GET | `/conversations` | 会话列表 |
| GET | `/conversations/{id}/messages` | 会话消息 |
| GET | `/profile` | 用户画像 |
| POST | `/profile/confirm` | 确认画像变更 |
| GET | `/profile/events` | 画像变更事件 |
| POST | `/resumes` | 上传简历 |
| POST | `/plans` | 创建学习计划 |
| GET | `/plans/jobs/{jobId}` | 查询计划生成任务 |
| GET | `/plans/today` | 今日任务 |
| POST | `/plans/checkin` | 学习打卡 |
| GET | `/career/gaps` | 查询岗位能力差距 |
| POST | `/career/gaps/tasks` | 将差距转为学习任务 |
| POST | `/interview/open` | 创建并持久化场景蓝图。Body: `{ targetRole, jobDescription?, interviewType?, difficulty?, durationMinutes? }`；类型为 `technical`、`project`、`system_design` 或 `behavioral`；按用户限流 |
| POST | `/interview/{sessionId}/answer` | 提交回答，Body: `{ answer, requestId }`；`requestId` 用于安全重试；按用户限流 |
| POST | `/interview/{sessionId}/retest` | 从当前用户已完成的面试创建同配置复测；新会话关联来源并规避原题 |
| POST | `/interview/{sessionId}/cancel` | 主动结束进行中的面试，按已完成题目生成阶段性复盘 |
| POST | `/interview/{sessionId}/feedback` | 提交 `accurate` 或 `inaccurate` 的评分校准反馈，附带可选原因 |
| GET | `/interview/{sessionId}/report` | 获取复盘；复测报告额外返回原测基线、分数变化与原始薄弱点（题目不同，仅作趋势比较） |
| GET | `/interview/{sessionId}` | 恢复当前用户尚未结束的面试会话 |
| GET | `/interview/{sessionId}/report` | 查询当前用户的面试复盘报告 |
| GET | `/notifications` | 查询通知 |
| POST | `/notifications/read` | 标记通知已读 |
| GET | `/memory/consent` | 查看外部记忆授权 |
| DELETE | `/memory/consent` | 关闭授权并删除记忆 |
| GET | `/memories` | 查看本地跨会话记忆 |
| DELETE | `/memories/{id}` | 删除单条本地跨会话记忆 |
| DELETE | `/memories` | 清除所有跨会话记忆；云端删除异步处理 |
| GET | `/memories/remote-deletion` | 查看云端记忆删除状态 |

## 4. SSE `/chat` 事件

请求示例：

```json
{
  "conversationId": 12,
  "message": "我想从零开始学习 RAG，应该先学什么？"
}
```

| 事件 | 主要字段 | 前端用途 |
| --- | --- | --- |
| `meta` | `conversation_id`, `trace_id` | 建立本轮上下文 |
| `stage` | `phase`, `expert?`, `status?`, `detail?` | 展示路由、检索和专家状态；专家状态为 `success`、`timeout`、`failed`、`cancelled` 或 `rejected` |
| `clarify` | `question` | 需要澄清时暂停 |
| `citation` | `sid`, `node_id`, `text`, `source_url`, `source_status`, `evidence_hash`, `graph_path` | 证据卡片；`managed` 表示平台固化且哈希一致，外部来源默认 `unverified`，哈希异常为 `integrity_mismatch`；请求链路不会主动抓取 URL |
| `token` | `text`, `seq` | 顺序拼接流式回答 |
| `done` | `message_id`, `citation_status`, `citation_issues` | 完成本轮；引用校验可能先为 `pending`，历史消息会返回异步完成后的状态 |
| `error` | `code`, `message` | 展示用户友好错误 |

## 5. 管理端

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/admin/overview` | 管理概览 |
| GET | `/admin/users` | 用户列表 |
| POST | `/admin/users/{id}/disable` | 禁用用户 |
| POST | `/admin/users/{id}/restore` | 恢复用户 |
| POST | `/admin/users/{id}/soft-delete` | 软删除用户 |
| GET | `/admin/audit` | 审计日志 |
| POST | `/admin/documents` | 上传 PDF/DOCX/TXT/Markdown |
| GET | `/admin/documents` | 文档列表和处理状态 |
| POST | `/admin/documents/{id}/retry` | 重试文档处理 |
| POST | `/admin/documents/{id}/soft-delete` | 删除文档及检索分块 |
| GET | `/admin/interview-evals/annotations/queue` | 获取去身份化的待双人标注面试回答；默认盲评、每个 session 默认最多 1 题，用户不准确反馈和低置信度样本优先 |
| POST | `/admin/interview-evals/annotations/{questionId}` | 提交或幂等更新人工评分与理由 |
| POST | `/admin/interview-evals/annotations/replay` | 聚合达到最少评审人数的样本并运行评分门禁 |

## 6. 内部评测端点

以下端点只用于本地评测和工作台，生产 profile 强制关闭：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/internal/retrieve` | 调用指定检索模式 |
| POST | `/internal/route` | 评测意图路由 |
| POST | `/internal/evals` | 启动评测 |
| GET | `/internal/evals` | 评测历史 |
| GET | `/internal/evals/{id}` | 评测详情 |
| GET | `/internal/feedback/summary` | 内部反馈汇总 |
