# 贡献指南

## 1. 修改前先确定边界

提交功能前请说明：修改的是业务逻辑、检索质量、性能、安全还是文档；是否改变数据库结构、API 或 SSE 契约；是否需要新的环境变量；如何测试和回滚。

## 2. 推荐工作流

```text
Issue / 目标
   ↓
阅读相关架构和 ADR
   ↓
新增或修改测试
   ↓
实现最小变更
   ↓
运行后端测试、前端构建和必要的集成测试
   ↓
如果涉及 RAG，跑固定数据集并保留前后对比
   ↓
更新文档、迁移说明和 Changelog
```

## 3. RAG 变更要求

涉及 Chunk 切分、Embedding 模型、RRF 参数、图扩展关系或配额、Query 路由、Rerank、多跳结束条件时，至少跑一轮评测。

提交中应记录数据集版本、检索模式、Recall/Hit/MRR、P50/P95、受影响切片、新增或消失的 Badcase，以及是否改变发布门禁。

## 4. 数据库迁移

数据库变更使用 Flyway：

```text
backend/src/main/resources/db/migration/V{number}__{description}.sql
```

规则：已执行的迁移文件不修改；新增迁移可审查；删除或软删除要说明外键影响；需要回滚时提供人工操作说明；迁移后运行数据库集成测试。

## 5. 提交前检查

```powershell
cd backend
mvn verify

cd ..\frontend
npm run build
npm run e2e
```

如未运行某项检查，请在 Pull Request 中说明原因。提交前确认没有 `.env`、密钥、Token、生产日志、真实简历或用户标识；新接口有用户隔离和权限校验；失败响应没有泄露堆栈；文档中的命令和配置名称与代码一致。

## 6. Issue 分类

- `bug`：现有功能与预期不一致；
- `retrieval-quality`：Gold 未召回、排序退化或引用不忠实；
- `performance`：延迟、内存或并发问题；
- `security`：权限、隐私、密钥或输入校验问题；
- `documentation`：启动、架构或 API 文档缺失；
- `good-first-issue`：适合学习者的独立任务。
