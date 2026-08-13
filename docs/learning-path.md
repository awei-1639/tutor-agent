# 学习专栏：从能运行到能解释、能评测、能扩展

## 怎么学

每个主题按同一个节奏完成：

1. 先看“本节交付”，明确做完后应该多出什么证据。
2. 找到代码入口，只读完成当前问题所需的路径，不从全仓库漫游。
3. 执行验证命令或页面操作，把输出、截图、指标或测试名记录下来。
4. 用一句话解释设计取舍，再做本节作业。
5. 只有达到“通过条件”才进入下一节；失败用例进入 [Badcase 卡片](badcases.md)。

建议先完整走一遍 00～06，再做 07 扩展。总时长约 1～2 个周末，取决于是否已经熟悉 Spring Boot、React 和 Docker。

## 专栏目录

| 编号 | 主题 | 建议用时 | 本节交付 |
| --- | --- | ---: | --- |
| 00 | [先跑通项目](#00-先跑通项目) | 30 分钟 | 环境清单、健康检查结果、首屏截图 |
| 01 | [追踪一次聊天请求](#01-追踪一次聊天请求) | 60 分钟 | 请求链路图、SSE 事件说明、测试证据 |
| 02 | [拆开混合检索](#02-拆开混合检索) | 90 分钟 | 四种检索策略对比表、失败用例分类 |
| 03 | [理解多智能体编排](#03-理解多智能体编排) | 90 分钟 | 意图到专家的路由图、超时/失败收口说明 |
| 04 | [记忆、上下文与隐私](#04-记忆上下文与隐私) | 90 分钟 | 记忆准入规则、上下文预算说明、降级证据 |
| 05 | [从岗位缺口到成长任务](#05-从岗位缺口到成长任务) | 60 分钟 | 一条可验收的学习任务、幂等和证据字段 |
| 06 | [跑一次真实 RAG 评测](#06-跑一次真实-rag-评测) | 120 分钟 | 测评集版本、指标截图、失败样本复盘 |
| 07 | [做一次工程化扩展](#07-做一次工程化扩展) | 180 分钟 | 一个小 PR、测试、文档和回归记录 |

---

## 00｜先跑通项目

### 本节目标

证明“服务活着”“依赖可用”“用户可以进入核心页面”是三个不同结论。

### 操作

1. 按 [本地开发](local-development.md) 准备 `.env`，启动 PostgreSQL 和 Neo4j。
2. 启动后端和前端，完成注册或开发登录。
3. 分别检查：

```text
GET http://localhost:8180/healthz
GET http://localhost:8180/readyz
```

4. 打开聊天、个人画像、成长计划和 RAG 评测页面，记录哪些页面需要数据库或 LLM。

### 交付物

- 一张“环境 → 服务 → 页面”的依赖表。
- `/healthz` 与 `/readyz` 的实际返回结果。
- 一张核心页面可用的截图；若不可用，附上 [Badcase 卡片](badcases.md) 而不是只写“启动失败”。

### 通过条件

- 能区分健康检查和就绪检查。
- 能说明 Neo4j 或 LLM 暂不可用时，哪些能力应降级、哪些能力应明确提示。
- 能复述后端、前端和数据库的启动命令。

---

## 01｜追踪一次聊天请求

### 本节目标

从 HTTP 入口追到最终 SSE 输出，理解流式响应为什么需要上下文隔离、顺序控制和失败收口。

### 代码入口

```text
backend/src/main/java/com/tutor/chat/api/ChatController.java
backend/src/main/java/com/tutor/chat/application/ChatService.java
backend/src/main/java/com/tutor/expert/IntentRouter.java
backend/src/main/java/com/tutor/expert/ExpertRunner.java
backend/src/main/java/com/tutor/expert/Aggregator.java
backend/src/main/java/com/tutor/context/PromptAssembler.java
```

### 交付物

画出下面这条链路，并给每个节点标注输入、输出和失败策略：

```text
请求鉴权 → 意图路由 → 上下文组装 → 检索/专家执行 → 结果仲裁 → SSE 输出 → 会话落库
```

### 验证重点

- 事件是否带有可排序的序号或等价顺序信息。
- 重复、乱序、断流和单专家超时分别由哪一层处理。
- 用户 A 的会话、记忆和引用是否可能被用户 B 看到。

### 通过条件

不要求背类名，但必须能回答：“如果一个专家超时，为什么用户仍能收到有边界的回答？如果流中间断开，哪些数据仍然应该保留？”

---

## 02｜拆开混合检索

### 本节目标

把“RAG 效果好”拆成可观察的检索链路，而不是把结果归因给一个黑盒模型。

### 代码入口

```text
backend/src/main/java/com/tutor/retrieval/vector/VectorStore.java
backend/src/main/java/com/tutor/retrieval/graph/GraphStore.java
backend/src/main/java/com/tutor/retrieval/fusion/FusedRetriever.java
backend/src/main/java/com/tutor/retrieval/agentic/AgenticRetriever.java
backend/src/main/java/com/tutor/retrieval/resilience/Neo4jResilience.java
```

### 操作

1. 先运行小样本评测：`node evals/run_eval.mjs --smoke`。
2. 在 RAG 评测页面或评测脚本中分别观察 `vector_only`、`fused`、`fused_rerank` 和 `agentic`。
3. 选 3 个失败用例，分别归类为：召回不到、排序靠后、图谱扩展错误、证据不完整或问题本身不可回答。

### 交付物

| 用例 | 预期证据 | 实际 Top-K | 根因 | 下一步动作 |
| --- | --- | --- | --- | --- |
| 例：技能前置关系 | 前置节点 |  |  |  |

### 通过条件

- 能解释 Recall@K 和 MRR@K 的差异。
- 能说明图谱扩展为什么需要深度、节点数或关系类型边界。
- 能说明 Neo4j 熔断后为什么不能继续假装使用图谱结果。

---

## 03｜理解多智能体编排

### 本节目标

理解“多智能体”不是简单地多调用几个模型，而是把路由、并发、证据、仲裁和降级变成可治理流程。

### 交付物

为一个问题（例如“我适合这个岗位吗”）写一张编排卡：

```text
意图：
需要的专家：
共享上下文：
并发边界：
必须返回的证据：
单专家失败后的替代路径：
最终答案的停止条件：
```

### 通过条件

- 能区分“专家并发”与“无边界并发”。
- 能指出 LLM 预算闸门、并发闸门和引用护栏分别限制什么风险。
- 能解释最终答案为什么需要仲裁，而不是把专家文本直接拼接。

---

## 04｜记忆、上下文与隐私

### 本节目标

把短期会话、摘要、情节记忆、用户画像和外部 Mem0 分成不同层次，并明确每层的准入和回收边界。

### 代码入口

```text
backend/src/main/java/com/tutor/memory/application/LongTermMemoryService.java
backend/src/main/java/com/tutor/memory/local/ConversationStore.java
backend/src/main/java/com/tutor/memory/local/EpisodeRecall.java
backend/src/main/java/com/tutor/memory/external/Mem0Client.java
backend/src/main/java/com/tutor/memory/policy/MemoryConsentService.java
backend/src/main/java/com/tutor/context/PromptAssembler.java
```

### 交付物

画一张“信息生命周期”图，至少包括：产生、筛选、写入、召回、注入上下文、用户撤回、过期/删除。

### 通过条件

- 能说明为什么“用户说过”不等于“可以永久记住”。
- 能说明 Mem0 不可用时的本地降级边界。
- 能解释 Token budget 如何避免历史记忆挤占当前问题和证据。

---

## 05｜从岗位缺口到成长任务

### 本节目标

把岗位匹配从一个分数，推进成用户可以执行、系统可以追踪、完成后可以验证的成长任务。

### 交付物

为一个技能缺口填写任务卡：

```text
目标技能：
缺口证据：
前置技能：
任务动作：
完成证据：
验收方式：
重复提交如何处理：
```

### 通过条件

- 任务不是“学习一下 Java”，而是一个可观察动作，例如提交测试报告、实现一个接口或通过一组测评。
- 能指出岗位、技能、前置关系、任务和完成证据之间的数据来源。
- 重复推送不会生成重复任务或重复通知。

---

## 06｜跑一次真实 RAG 评测

### 本节目标

使用仓库中的真实测评集跑出可视化或脚本结果，并且能从失败样本反推工程改动。

### 操作

1. 阅读[评测手册](evaluation.md)，确认测评集版本、类别、Top-K 和阈值。
2. 先跑 `node evals/run_eval.mjs --smoke` 验证数据和环境。
3. 再跑完整测评或打开前端 RAG 评测页面，保存总览指标和分类结果。
4. 至少选 5 个失败或边界用例，记录“预期证据、实际证据、失败根因、修复假设”。
5. 与历史运行对比，避免只报告一个漂亮的总分。

### 交付物

- 测评集版本和样本量。
- Recall@K、MRR@K、章节/证据锚点命中、不可回答通过率和耗时。
- 至少一张分类成绩截图或等价 JSON 结果。
- 失败用例复盘表和下一轮实验假设。

### 通过条件

指标必须能回溯到样本，样本必须能回溯到证据；否则只能称为“演示结果”，不能称为评测结论。

---

## 07｜做一次工程化扩展

### 本节目标

在不破坏现有边界的前提下完成一个小改动，并用测试、文档和回归结果证明它。

### 可选题目

- 新增 5 条带 gold 节点和证据锚点的 RAG 测评用例。
- 给一个已有 Badcase 补充自动化检查。
- 增加一种失败专家的降级观测字段。
- 为一个管理端操作补充权限、审计或幂等说明。
- 为新的后端模块补齐模块 README 和测试入口。

### 交付物

代码或数据变更、测试命令及结果、文档更新、一个失败场景的回归证据。

### 通过条件

变更必须同时回答四个问题：改了什么、为什么改、怎么证明没破坏旧行为、出现问题如何回滚或降级。
