# Frontend

React 18 + TypeScript + Vite 前端，默认开发地址为 `http://localhost:5173`。

## 开发入口

```powershell
npm install
npm run dev
npm run build
npm run e2e
```

Vite 将 `/api` 代理到 `http://127.0.0.1:8180`。页面入口在 `src/pages`，跨页面组件在 `src/components`，后端请求和 SSE 解析集中在 `src/lib/api.ts`。

## 页面边界

- `ChatPage`：聊天和流式事件消费；
- `ProfilePage`、`ResumePage`：用户画像和简历；
- `PlansPage`、`InterviewPage`：成长计划和模拟面试；
- `RagEvalPage`：真实 RAG 评测可视化；
- `AdminPage`、`KnowledgeBasePage`：管理员和知识库；
- `LoginPage`、`NotificationsPage`：认证和通知。

新增页面应通过统一 API 客户端访问后端，不在页面中复制 Cookie、CSRF 或错误格式化逻辑。

