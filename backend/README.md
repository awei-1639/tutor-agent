# Backend

Java 21 + Spring Boot 3.4 的后端服务，默认监听 `8180`。

## 开发入口

```powershell
mvn spring-boot:run
mvn test
mvn test -DrunIntegrationTests=true -Dtest='*IT'
```

详细环境准备见 [本地开发手册](../docs/local-development.md)，模块边界见 [项目结构](../docs/project-structure.md)。

## 代码组织

后端按业务能力分包：认证、聊天、检索、记忆、画像、计划、推送、知识库和评测等模块各自维护 Controller、Service 和领域逻辑。聊天、检索、记忆内部已经进一步按 API/application、检索通道、本地/外部记忆拆分。不要新增一个横跨所有模块的 `common` 或 `utils` 包来规避边界。

主要基础设施：

- PostgreSQL + Flyway：业务数据、会话、任务、文档分块和评测结果；
- pgvector + pg_trgm：向量和稀疏检索；
- Neo4j：技能关系和受控图扩展；
- 外部 LLM/Embedding/Rerank：统一经过 `llm/LlmGateway`；
- Mem0、OSS：可选增强能力，均有失败降级边界。

## 迁移规则

数据库迁移位于 `src/main/resources/db/migration`，已执行文件不可修改，只能追加新的 `V{number}__description.sql`。
