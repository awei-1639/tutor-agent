# Spikes（丢弃式验证，验证完提炼结论到本文件后删除代码）

本目录代码**允许是垃圾代码**，禁止被 backend/ 引用。每个 spike 半天封顶。

## Spike 1：DeepSeek 结构化输出稳定性 ✅ 已完成（2026-07-26）
- 做法：ExpertOutput 形状的 JSON schema（字段+类型+枚举校验），response_format=json_object + system prompt 内嵌 schema，50 次调用并发 5，temperature=0
- **结论：失败率 0/50（parse 0、schema 0、HTTP 0），p50=102ms / p95=166ms（固定 prompt 疑似命中服务端缓存，真实延迟会更高）。命中"轻量重试即可"档位**
- 对正式实现的约束：
  1. 网关只做失败重试 1 次（附上次错误信息回传自纠），不引入重量级约束解码
  2. 服务端两层校验函数必须保留（JSON.parse + 字段/类型/枚举），真实多样 prompt 下长尾失败率可能略升
  3. LLM 超时生产配 10~15s 足够（当前 60s 过于宽松）；deepseek-chat 现指向 v4-flash，延迟预算可按亚秒级设计
  4. 上线前用多样化真实 prompt 重新压测 p95 再定超时值

## Spike 2：pgvector + bge-m3 中文检索质量 ✅ 已完成（2026-07-26）
- 做法：20 条 kg_chunk 模板格式文本（技能/资源/岗位）经 SiliconFlow bge-m3 入 pgvector，10 条中文查询（口语化/专名/岗位反查）验证 top5
- **结论：Hit@5 = 10/10，Hit@1 = 7/10，Hit@2 = 10/10。专有名词（"西瓜书"0.52 vs 次名 0.39 分差显著）、中文同义改写（"检索增强生成"→RAG）、口语查询（"零基础学AI"）全部正确命中；未命中 top1 的 3 条其 top1 在语义上同样合理（如"神经网络入门"→深度学习排第一）。30 条文本 embedding 仅 2 秒**
- 对正式实现的约束：
  1. chunk 模板 `类型|名称|关键属性|一跳关系摘要` 有效，正式管线沿用
  2. bge-m3 余弦分数分布集中在 0.4~0.7：相似度阈值不宜设高，L2 情景记忆的 0.5 阈值量级合适，待真实数据校准
  3. embedding 批量接口一次可传 ≥30 条，种子数据 500 条向量化预计 <1 分钟、成本可忽略
  4. SiliconFlow API 延迟表现良好，无需本地部署（印证 ADR）

## Spike 3：Neo4j 一跳扩展查询 ✅ 已完成（2026-07-26）
- 做法：30 节点/46 边的技能-资源-岗位-公司图，白名单边一跳扩展 + 多跳路径查询 + 延迟采样，测完清零
- **结论：白名单扩展结果完全正确（NLP 扩出前置/资源/岗位，正确排除非白名单边）；预热后延迟均值 6.4ms（目标 <50ms），首次查询含计划编译 ~420ms；python→transformer 多跳路径 `*1..4` 返回 2 条完整链；全程纯 Cypher，不需要 APOC**
- 对正式实现的约束：
  1. 一跳扩展用 `MATCH (n)-[r:PREREQUISITE|TEACHES|LEADS_TO]-(m)` 白名单模式，无向匹配；路径查询用有向匹配——组合已验证可行
  2. 正式建库必须给 node_id 建唯一约束（`CREATE CONSTRAINT ... REQUIRE n.node_id IS UNIQUE`）
  3. 应用侧复用 driver/session 摊薄首次 ~400ms 的计划编译开销
  4. 多跳查询设跳数上限（*1..4）并加 LIMIT，防图变大后路径爆炸
  5. 边方向语义固定：前置技能→[:PREREQUISITE]→后续技能、资源→[:TEACHES]→技能、技能→[:LEADS_TO]→岗位、岗位→[:REQUIRES]→技能（已同步图谱 schema 认知）
