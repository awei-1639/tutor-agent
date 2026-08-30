# 检索 Facet 标注口径

`retrieval_facets` 决定一次请求要在知识图谱上扩展哪些关系。它不是「这个问题属于什么话题」的主题标签，而是**「这次检索需要走哪几类图关系」的执行指令**。标注时唯一要问的问题是：*把这类关系扩进来，能不能帮到这个回答？*

## 三个 facet 的机械含义

口径必须回到代码。`GraphExpansionPolicy.forFacets`（`backend/src/main/java/com/tutor/retrieval/graph/GraphExpansionPolicy.java:30`）把 facet 映射成具体的图关系：

| facet | 扩展的关系 | 方向 | 种子数据里的来源 |
| --- | --- | --- | --- |
| `career` | `REQUIRES`、`LEADS_TO` | 出边 | `seed_jobs.json` 的 `requires`：岗位 → 所需技能 |
| `learning` | `PREREQUISITE`、`TEACHES` | 入边 | `seed_skills.json` 的 `prerequisites` / `advances_to`：技能之间的先后依赖 |
| `resource` | `TEACHES` | 入边 | `seed_resources.json` 的 `teaches`：课程/书籍 → 所教技能 |

注意 `learning` 已经包含 `TEACHES`（权重 0.90，略低于 `resource` 的 1.0）。所以 `learning` 与 `resource` 并列时，唯一的增量是把 `TEACHES` 的权重提到 1.0 —— 这是个**权重微调，不是新增召回通道**。这一点直接决定了下面的规则 3。

## 标注规则

按顺序套用，命中即停。

**规则 1：`out_of_scope` 一律为空。**
与 AI 学习/求职无关的请求不做任何图扩展。`IntentRouter.parseRetrievalFacets` 对此有硬校验（`OUT_OF_SCOPE_FACETS_FORBIDDEN`）。

**规则 2：问题落在哪类实体上，就给对应 facet。**
- 涉及**具体岗位**的要求、投递、匹配度、薪资、市场行情 → `career`
- 涉及**技能之间的先后顺序**、学什么、先学哪个、要多久 → `learning`
- 明确在**找课程、书籍、资料、题库**，或问某个具体资料好不好 → `resource`

**规则 3：不要为「话题上沾边」而叠加 facet，只为「关系上确实需要」而叠加。**

这是最容易错的地方，也是本次重标的主要动因。判据是上面那张表：叠加一个 facet，要能指出它带来了哪条**新的**关系。
- `career` + `learning`：只在问题**同时**要求「岗位需要什么」和「这些技能之间怎么排先后」时才给。典型是综合诉求（`mixed`）。
- `learning` + `resource`：几乎不需要。`learning` 已含 `TEACHES`，叠加 `resource` 只改权重。**除非问题的主体就是资料本身**，此时应当单给 `resource`。

**规则 4：纯概念解释给空。**
「什么是 X」「X 和 Y 有什么区别」这类问题靠向量/稀疏检索的文本片段就能答好，图关系帮不上忙。给空，省一次图扩展。

**规则 5：怀疑时给窄的那个。**
多给一个 facet 的代价是实打实的：多一轮图查询、更多候选进入 RRF 融合、更可能把无关节点挤进 TopK。少给的代价是可能漏召回。在这个项目里前者更常见，因为 `FusedRetriever` 有 per-source 配额，无关的图候选会挤掉直接命中的文本块。

## 边界案例

这些是 2026-08-30 重标时实际争议过的，记下来避免下次重新争一遍。

| 问题类型 | 判定 | 理由 |
| --- | --- | --- |
| 「明天要面试大模型岗，帮我模拟一下」 | `learning` | 要的是知识点覆盖（技能依赖），不是岗位 JD 的硬性要求列表。给 `career` 会扩进 `REQUIRES` 出边，召回一堆岗位节点，对模拟面试没用。 |
| 「面试官问我为什么转行，该怎么回答」 | 空 | 这是沟通话术，图谱里没有任何关系能支撑它。既不是技能依赖也不是岗位要求。 |
| 「简历投大模型岗没回音，是不是内容有问题」 | `career` | 落在岗位要求 vs 简历内容的匹配上，`REQUIRES` 正是要扩的。技能先后顺序在这里无关。 |
| 「西瓜书适合入门看吗」 | `resource` | 主体是资料本身。按规则 3，不叠加 `learning`。 |
| 「我该先学机器学习还是直接上手 LangChain」 | `learning` | 典型的 `PREREQUISITE` 问题——问的就是两个技能的先后。 |
| 「什么是检索增强生成」 | 空 | 规则 4，纯概念。 |
| 「杭州的 NLP 岗位多吗」 | `career` | 岗位市场信息，落在 job 节点上。 |
| 「帮我准备算法岗的系统设计面试」 | `learning` | 同模拟面试：要的是知识点结构。「算法岗」是限定语境，不是要查 JD。 |

## 与评测的关系

`evals/router_testset.json` 的 facet 标签按本文口径维护。`docs/evaluation.md` 里 `router_facet_exact_match >= 0.85`、`router_facet_macro_f1 >= 0.80` 两个门禁针对的就是这套标注。

改动标注必须说明**改了哪几条、为什么**，否则指标变化无法区分「模型变好」和「标签变宽松」——这是 `CLAUDE.md` 对数据集纪律的要求。本文首次成文时的重标记录见 `docs/badcases.md` Badcase 09。

标注口径本身也可能是错的。如果某条标注反复与模型判断冲突、且模型那侧在检索效果上更合理，应当改标注并在这里补一行边界案例，而不是改 prompt 去迎合标注。

## Prompt 修订记录

- **2026-08-30**：30 条路由集逐条诊断（`evals/diag_facets.mjs`）显示 11 条 facet 误判中 7 条为过度叠加（违反规则 3）、2 条为纯概念/话术未给空（违反规则 4）、2 条漏给 learning。标签未改动；将规则 3、规则 4 的机械判据和「模拟面试给 learning 不给 career」的边界结论写入 `IntentRouter` 的系统提示。对应门禁：`router_facet_exact_match >= 0.85`。
