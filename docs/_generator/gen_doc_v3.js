const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  WidthType, HeadingLevel, AlignmentType, ShadingType, LevelFormat
} = require('docx');

const FONT = { ascii: 'Calibri', eastAsia: '微软雅黑' };
const CODE_FONT = { ascii: 'Consolas', eastAsia: '微软雅黑' };

function r(text, opts = {}) { return new TextRun({ text, font: FONT, size: 21, ...opts }); }
function p(children, opts = {}) {
  if (typeof children === 'string') children = [r(children)];
  return new Paragraph({ children, spacing: { after: 120, line: 300 }, ...opts });
}
function h1(text) {
  return new Paragraph({
    children: [new TextRun({ text, font: FONT, size: 30, bold: true, color: '1F3864' })],
    heading: HeadingLevel.HEADING_1, spacing: { before: 320, after: 160 },
  });
}
function h2(text) {
  return new Paragraph({
    children: [new TextRun({ text, font: FONT, size: 26, bold: true, color: '2E5395' })],
    heading: HeadingLevel.HEADING_2, spacing: { before: 240, after: 120 },
  });
}
function h3(text) {
  return new Paragraph({
    children: [new TextRun({ text, font: FONT, size: 22, bold: true, color: '404040' })],
    heading: HeadingLevel.HEADING_3, spacing: { before: 200, after: 100 },
  });
}
function bullet(boldPart, rest) {
  const children = [];
  if (boldPart) children.push(r(boldPart, { bold: true }));
  if (rest) children.push(r(rest));
  return new Paragraph({
    children, numbering: { reference: 'bullets', level: 0 },
    spacing: { after: 80, line: 300 },
  });
}
function num(text) {
  return new Paragraph({
    children: [r(text)], numbering: { reference: 'nums', level: 0 },
    spacing: { after: 80, line: 300 },
  });
}
function code(lines) {
  return lines.map(line => new Paragraph({
    children: [new TextRun({ text: line || ' ', font: CODE_FONT, size: 17 })],
    shading: { type: ShadingType.CLEAR, fill: 'F5F5F5' },
    spacing: { after: 0, line: 260 },
    indent: { left: 240 },
  }));
}
function cell(text, { width, header = false } = {}) {
  const lines = Array.isArray(text) ? text : [text];
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: header ? { type: ShadingType.CLEAR, fill: 'DCE6F1' } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: lines.map(t => new Paragraph({
      children: [r(t, { bold: header, size: 19 })],
      spacing: { after: 20, line: 260 },
    })),
  });
}
function table(headers, rows, widths) {
  return new Table({
    columnWidths: widths,
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    rows: [
      new TableRow({ tableHeader: true, children: headers.map((t, i) => cell(t, { width: widths[i], header: true })) }),
      ...rows.map(row => new TableRow({ children: row.map((t, i) => cell(t, { width: widths[i] })) })),
    ],
  });
}

const children = [];

// ============ 封面 ============
children.push(new Paragraph({
  children: [new TextRun({ text: '个人AI学习与求职助手 最佳设计方案（V3 工程落地版）', font: FONT, size: 40, bold: true })],
  alignment: AlignmentType.CENTER, spacing: { before: 200, after: 160 },
}));
children.push(new Paragraph({
  children: [new TextRun({ text: '面向真实工程约束的最终设计：单人4周 · 单机部署 · 面试价值最大化', font: FONT, size: 24, color: '595959' })],
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
}));

// ============ 一、设计原则 ============
children.push(h1('一、工程约束与设计原则'));
children.push(h2('1.1 真实约束'));
children.push(bullet('人力与工期：', '1人 × 4周，无运维支持，所有组件必须一条 docker compose up 拉起'));
children.push(bullet('项目定位：', '求职作品集项目。优化目标是"面试可讲的技术深度 + 可量化的评估数字"，不是功能数量'));
children.push(bullet('数据规模：', '技能节点约100、资源约200、岗位JD约200、向量总量 < 1万条——这是选型的决定性前提'));
children.push(h2('1.2 三条设计原则'));
children.push(num('组件最少化：每引入一个中间件都要回答"数据规模是否配得上它"。本方案存储从4个收敛到2个（Neo4j + PostgreSQL），砍掉的每一项都有决策记录（见第十章ADR），砍组件的理由本身就是面试素材'));
children.push(num('纵向切片交付：每周结束都有一个端到端可演示的版本，永远不出现"最后一周才串联"的集成灾难'));
children.push(num('一切结论有数字：检索质量、路由准确率、成本，全部用评估脚本量化，A/B对比写进README'));

// ============ 二、总体架构 ============
children.push(h1('二、总体架构'));
children.push(p('技术栈收敛为：应用框架 + Agent编排 + Neo4j + PostgreSQL（含pgvector）。相比初版方案砍掉 Qdrant、Redis、多租户设计与三平台真实抓取。'));
children.push(p([r('实现双轨说明（2026-07-26更新）：', { bold: true }), r('实现采用双轨并行设计——Java主线（Spring Boot 3 + LangChain4j + 手写轻量编排）与Python线（FastAPI + LangGraph）。本方案的全部架构决策语言无关，两条线的实现级差异见《核心实现设计_记忆机制与Context工程》Java版/Python版。下文示意与目录以Python线为例。')]));
children.push(...code([
  '┌─────────────────────────────────────────────────────┐',
  '│  前端  React + Ant Design X（现成对话组件, ≤4天）      │',
  '└──────────────────────┬──────────────────────────────┘',
  '                       │ SSE 流式',
  '┌──────────────────────▼──────────────────────────────┐',
  '│  FastAPI                                            │',
  '│  /chat(SSE)  /profile  /jobs  /eval                 │',
  '├─────────────────────────────────────────────────────┤',
  '│  LangGraph 工作流（PostgresSaver 持久化）             │',
  '│  profile_update → router ─┬→ resume_expert ─┐       │',
  '│                           ├→ interview_expert├→ agg │',
  '│                           ├→ planner_expert ─┘  ↓   │',
  '│                           └→ direct_answer → respond│',
  '├──────────────┬──────────────────────────────────────┤',
  '│ Neo4j        │ PostgreSQL (+pgvector)               │',
  '│ 技能/资源/岗位│ 用户/画像/JD/向量/LangGraph状态/任务   │',
  '│ 图谱与多跳推理│                                      │',
  '└──────────────┴──────────────────────────────────────┘',
  '  横向工具：job_matcher / pusher(APScheduler) / pii脱敏',
]));
children.push(h2('2.1 关键收敛决策'));
children.push(table(
  ['原方案', 'V3决策', '工程理由'],
  [
    ['Qdrant 向量库', 'PostgreSQL + pgvector', '向量总量<1万，pgvector HNSW索引毫秒级返回；少一个存储组件，向量与业务数据同库支持JOIN过滤'],
    ['Redis（缓存/队列）', '进程内TTL缓存 + PG任务表', '单机单进程场景，APScheduler进程内调度即可；embedding缓存用functools+磁盘缓存'],
    ['三平台真实抓取', 'Mock为主 + 可选1个合规渠道', '反爬对抗投入产出比极低且不可控；200条真实JD快照保证全链路可演示'],
    ['多租户SaaS', '单用户（保留user_id字段）', '多租户是纯复杂度负债，对作品集不加分；字段预留即可体现扩展意识'],
    ['独立微服务划分', '单体FastAPI应用', '单人项目微服务只会放大联调成本；模块边界用Python包结构表达'],
  ],
  [1900, 2500, 4600]
));

// ============ 三、LangGraph工作流 ============
children.push(h1('三、LangGraph 工作流设计（核心卖点一）'));
children.push(h2('3.1 状态定义'));
children.push(...code([
  'class AgentState(TypedDict):',
  '    messages: Annotated[list[AnyMessage], add_messages]',
  '    profile: dict            # 当前画像快照（含置信度）',
  '    intent: str              # resume | interview | planning | mixed | chat',
  '    evidences: list[Evidence] # RAG召回结果，含来源节点id与得分',
  '    expert_outputs: dict     # {expert_name: ExpertOutput}',
  '    clarify_question: str | None  # 分歧兜底时的澄清问题',
]));
children.push(h2('3.2 节点与路由'));
children.push(bullet('onboarding（首次会话引导）：', '检测画像为空时进入引导：三问（目标岗位/现有技能/每日可投入时间），或直接上传简历跳过；引导完成前不触发专家扇出——冷启动路径显式设计，不靠用户自发闲聊攒画像'));
children.push(bullet('profile_update：', '每轮先读取画像快照注入context；增量抽取（显式=1.0 / 推断=0.6起）在回答完成后异步执行并带信号门控——当轮新信息本就在对话context中，异步不损失本轮效果（与实现设计2.3口径一致）'));
children.push(bullet('router：', '轻量LLM调用输出意图标签。resume/interview/planning 单意图直达对应专家；mixed 才三专家并行（Send API 扇出）；chat 走 direct_answer，不动用专家——这一层省掉约2/3的无谓LLM调用'));
children.push(bullet('experts：', '三专家共用一个 BaseExpert（同一RAG上下文注入 + structured output），差异仅在system prompt与输出schema，不做过度类设计'));
children.push(bullet('aggregator：', '汇总专家结构化输出。平均置信度≥0.6且无互斥结论→融合成统一行动方案；否则填充 clarify_question 转向用户澄清'));
children.push(bullet('持久化：', 'PostgresSaver checkpointer，thread_id=会话id。多轮对话、中断恢复、历史回放免费获得'));
children.push(h2('3.3 流式输出'));
children.push(p('FastAPI 端点用 graph.astream_events 转 SSE：router决策、专家进度、最终回答分阶段推送，前端可见"正在咨询简历专家…"的过程反馈——体验好且演示效果直观。'));
children.push(h2('3.4 专家输出Schema（示例：简历专家）'));
children.push(...code([
  'class ResumeAdvice(BaseModel):',
  '    match_score: float            # 0~1 简历-目标岗位匹配度',
  '    gaps: list[SkillGap]          # 缺口技能，关联图谱节点id',
  '    suggestions: list[Suggestion] # 修改建议，每条带理由与优先级',
  '    confidence: float             # 专家自评置信度',
  '    citations: list[str]          # 引用的知识节点id',
]));

// ============ 四、RAG融合检索 ============
children.push(h1('四、RAG + 图谱融合检索（核心卖点二）'));
children.push(h2('4.1 检索管线'));
children.push(...code([
  'def retrieve(query, top_k=5):',
  '    # 1) pgvector 稠密召回 top20（bge-m3, API embedding + 磁盘缓存）',
  '    vec_hits = pg.vector_search(embed(query), k=20)',
  '',
  '    # 2) Neo4j 受控扩展：白名单边一跳，限量30',
  '    #    prerequisite / teaches / leads_to',
  '    graph_hits = neo4j.expand([h.node_id for h in vec_hits],',
  '                              edges=WHITELIST, limit=30)',
  '',
  '    # 3) RRF融合（扩展节点排名乘衰减 α=0.7），可选bge-reranker精排',
  '    return rrf_fuse(vec_hits, graph_hits, alpha=0.7)[:top_k]',
]));
children.push(bullet('Embedding/Rerank供给形态：', 'bge-m3与bge-reranker-v2-m3均走SiliconFlow API（OpenAI兼容、无GPU依赖、Java/Python共用同一HTTP出口，经LLM网关统一管理）；本地TEI容器为可选替代（触发条件：月embedding成本>¥50或合规要求原文不出域）。API路径下简历等敏感文本一律脱敏后再向量化'));
children.push(h2('4.2 图谱的不可替代场景'));
children.push(p('面试必问"为什么向量检索不够"。准备好两个具体case：'));
children.push(bullet('前置链推理：', '"零基础如何成为NLP工程师"——答案需要沿 prerequisite 边回溯完整技能链（Python→ML→DL→NLP），向量相似度召回不出"隔了两跳"的前置技能'));
children.push(bullet('岗位反向拆解：', '"这个JD我还缺什么"——requires 边集合与画像技能集合的差集运算，是图查询而非相似度问题'));
children.push(h2('4.3 评估（简历数字的来源）'));
children.push(bullet('标注集：', '100条查询→黄金引用节点，覆盖单跳/多跳/岗位拆解三类，第1周先建50条'));
children.push(bullet('指标：', 'Recall@5、引用准确率、P95延迟'));
children.push(bullet('A/B报告：', 'evals/run_eval.py --mode vector_only 与 --mode fused 各跑一遍，对比表自动写入README——这组数字是整个项目最硬的简历素材'));

// ============ 五、数据模型 ============
children.push(h1('五、数据模型'));
children.push(h2('5.1 Neo4j 图谱Schema'));
children.push(p('节点：skill / resource / job / company；边：prerequisite、advances_to、teaches、leads_to、requires。种子数据：技能100、资源200、岗位200，LLM从公开路线图与JD批量抽取三元组，人工抽检≥30%后导入；低置信三元组进staging_triples待审核表，不直接入图。'));
children.push(bullet('节点属性Schema：', 'skill{name, aliases[], description, difficulty, est_hours}；resource{title, description, format, duration, url, language}；job{title, company, city, requires_raw[], jd_snapshot, fetched_at}；company{name, industry, size}。aliases是技能实体对齐的基础（见6.0），抽取prompt与导入脚本以此Schema为契约'));
children.push(h2('5.2 PostgreSQL 核心表'));
children.push(table(
  ['表', '用途', '要点'],
  [
    ['users / profiles', '用户与画像', '画像字段带 confidence/source/updated_at；敏感字段pgcrypto加密'],
    ['profile_events', '画像变更审计', '每次增量与触发来源，支持回放与调试'],
    ['jobs', 'JD快照', '含结构化requires技能列表与原文，embedding列(pgvector)'],
    ['kg_chunks', '知识节点文本+向量', 'node_id关联Neo4j，embedding列，HNSW索引；chunk文本=模板化序列化（类型|名称|关键属性|一跳关系摘要），模板进prompts/管理'],
    ['resumes / pii_mappings', '简历与脱敏映射', '原文加密存储、结构化字段jsonb、简历向量（脱敏后文本计算）；PII占位符映射表加密'],
    ['staging_triples', '图谱待审核三元组', '低置信抽取结果入此表，审核通过才入图（对应V4流水线）'],
    ['notifications', '用户可见消息', '推送触达的载体：站内消息、已读/未读，与push_tasks（执行状态）分离'],
    ['push_tasks', '推送任务与结果', 'APScheduler任务状态、重试次数、失败原因'],
    ['checkpoints', 'LangGraph状态', 'PostgresSaver自动管理'],
  ],
  [2200, 2400, 4400]
));

// ============ 六、岗位匹配与推送 ============
children.push(h1('六、岗位匹配与主动推送'));
children.push(h2('6.0 技能实体对齐（匹配/缺口/出题的共同前提）'));
children.push(p('画像技能与JD技能来自LLM自由文本抽取，而skill_coverage、缺口差集、prerequisite"可速成"判定、模拟面试出题全都要求映射到图谱skill节点id。三级对齐策略：'));
children.push(bullet('① 精确/别名命中：', 'skill节点aliases属性（"DL"/"深度学习"归一），别名表随种子数据人工维护'));
children.push(bullet('② 向量最近邻：', '未命中别名时用embedding相似度对齐，≥0.85自动归一，0.7~0.85进待确认队列'));
children.push(bullet('③ 未命中兜底：', '不参与图谱侧计算（从skill_coverage分母剔除），仅贡献语义相似度项；写入待对齐队列，未命中率>20%告警补别名——指标化防止对齐质量隐性劣化'));
children.push(p('对齐结果缓存为"技能名→node_id"映射表；对齐服务被画像、匹配、出题三处复用，只实现一次。'));
children.push(h2('6.1 匹配打分（可解释优先）'));
children.push(...code([
  'match = 0.6 * skill_coverage + 0.4 * semantic_sim',
  '# skill_coverage: JD requires技能被画像命中的加权比例，',
  '#   缺口技能若在画像技能的prerequisite一跳内，计0.5分（"可速成"）',
  '# semantic_sim: 简历全文向量 与 JD向量 余弦相似度',
]));
children.push(p('刻意不用纯LLM打分：规则+向量的组合可解释、可批量、零token成本，每个分数都能拆解展示"为什么推荐"——这是推荐理由卡片的数据来源，也是面试里"工程判断力"的体现。LLM仅用于对top结果生成一段推荐语。'));
children.push(h2('6.2 推送'));
children.push(bullet('调度：', 'APScheduler每日两次扫描jobs表新增记录，match≥0.65生成推送（站内消息+可选邮件）'));
children.push(bullet('降险：', '数据源默认seed_jobs.json（200条真实JD快照）；真实抓取作为独立可插拔source，封号/失败自动回落Mock，全链路演示不依赖外部平台'));
children.push(bullet('Mock投放策略：', '200条快照导入时保留100条为"注水池"，每日定时释放5~10条模拟新增——解决静态数据下"扫描新增记录"永远为空、F6无从演示的问题'));
children.push(bullet('触达（最后一公里）：', '推送产生notifications记录（站内消息、已读/未读），前端拉取展示；push_tasks只是执行状态表，两者分离。邮件为可选通道'));
children.push(bullet('画像/简历不完整时的降级：', '无简历→semantic_sim项置0，仅当skill_coverage≥0.5才推送；画像为空→不推岗位，改推引导消息（完善画像/上传简历）——冷启动期不推垃圾'));
children.push(bullet('可靠性：', '失败重试3次，push_tasks表记录状态与原因'));

// ============ 七、安全与成本 ============
children.push(h1('七、安全与成本控制'));
children.push(bullet('PII脱敏：', '简历入库前抽取姓名/手机号/邮箱建立占位符映射；外部LLM调用只见占位符，响应后本地还原。映射表加密存储。脱敏覆盖所有外呼——embedding API调用同样是外部请求，简历向量化必须在脱敏后文本上计算（PII对语义匹配无贡献），embedding本地部署时方可豁免'));
children.push(bullet('成本护栏：', 'deepseek-chat（约¥2/百万输入token）；每用户每日token限额；embedding磁盘缓存避免重复计费；意图路由天然省掉无谓的多专家调用。预估日常使用<¥5/天'));
children.push(bullet('删除权：', '一键删除接口清除画像、简历、对话与向量'));

// ============ 八、4周执行计划 ============
children.push(h1('八、4周执行计划（纵向切片）'));
children.push(table(
  ['周', '交付的可演示版本', '关键任务', '验收'],
  [
    ['W1', '丑但能用：提问→检索→单Agent回答带引用', 'docker-compose（PG+pgvector、Neo4j）；种子数据构建+审核+导入；embedding管线；最小对话链路；评估集50条', '端到端对话跑通；vector_only基线Recall@5有数'],
    ['W2', '多专家版：路由分流+专家评估+仲裁', 'router；BaseExpert+三专家structured output；aggregator与澄清兜底；PostgresSaver；SSE流式', '路由准确率>90%（30条测试语料）；mixed问题融合输出无互斥'],
    ['W3', '闭环版：画像持续更新+岗位匹配+每日推送', '画像置信度/衰减/确认机制；技能实体对齐服务；匹配打分器；APScheduler推送+notifications触达+Mock注水；PII脱敏', 'Mock数据下推送全链路100%跑通；匹配与人工判断一致率>80%（30样本）'],
    ['W4', '发布版：前端+评估报告+叙事', '评估集补至100条；A/B报告（vector_only vs fused）；前端对话+图谱路径可视化；README技术叙事', 'A/B对比数字落进README；三个"为什么"叙事完稿'],
  ],
  [700, 2600, 3600, 2900]
));
children.push(p([r('铁律：', { bold: true }), r('每周五版本必须可演示。任何一周延期，砍下一周的非核心项（优先砍：真实抓取渠道、reranker、图谱可视化），不砍评估。')]));

// ============ 九、验收标准 ============
children.push(h1('九、验收标准'));
children.push(table(
  ['编号', '功能', '验收标准'],
  [
    ['F1', '用户画像', '结构化画像含≥5个带置信度技能标签；显式声明覆盖推断；关键字段变更需确认'],
    ['F2', '图谱推理', '"如何成为NLP算法工程师"返回含完整前置链的学习路径，路径节点可视化'],
    ['F3', 'RAG检索', '50→100条图结构gold标注集：Hit@5≥85% 且 MRR高于纯向量基线 且 无切片比基线低5pp以上；引用准确率>90%；Recall@5作趋势跟踪不设绝对门槛（口径修订2026-07-26：多gold用例下top5容量使Recall@5≥85%数学不可达）'],
    ['F4', '路由与多专家', '路由准确率>90%；mixed问题三专家并行且仲裁无互斥；低置信触发澄清'],
    ['F5', '岗位匹配', '30份简历-JD样本与人工判断一致率>80%；每个分数可拆解解释'],
    ['F6', '主动推送', 'Mock数据下每日推送100%跑通；失败重试与状态可查'],
  ],
  [800, 1900, 6300]
));

// ============ 十、ADR ============
children.push(h1('十、技术决策记录（ADR，面试素材）'));
children.push(table(
  ['决策', '备选项', '选择理由（面试话术）'],
  [
    ['pgvector 而非 Qdrant', 'Qdrant/Milvus', '向量<1万条时pgvector足够且少运维一个组件，向量可与业务数据JOIN过滤；数据到百万级再迁专用向量库——展示"按规模选型"而非"按流行度选型"'],
    ['保留Neo4j', 'PG递归CTE建模图', '多跳路径查询与Cypher表达力是核心差异化；递归CTE三跳以上可读性和性能都差'],
    ['仲裁融合而非加权投票', '固定权重投票', '三专家输出维度不同，加权取最高在逻辑上不成立；"踩坑→重设计"本身是加分故事'],
    ['规则+向量匹配打分', '纯LLM打分', '可解释、可批量、零token成本；LLM只做top结果的推荐语生成'],
    ['意图路由前置', '所有请求全专家并行', '省约2/3专家调用成本，简单问题延迟从12s降到4s量级'],
    ['Mock优先的数据源设计', '强依赖真实抓取', '外部平台反爬不可控，可插拔source+自动回落保证任何时刻可演示'],
    ['embedding走SiliconFlow API', '本地TEI/Xinference容器', '无GPU依赖、compose不加容器、双语言同一HTTP出口；成本>¥50/月或合规触发时切本地，网关接口不变'],
    ['技能对齐三级降级', '仅精确字符串匹配', '自由文本技能名直接匹配命中率极低会拖垮F5；别名→向量→剔除分母的降级链+未命中率告警，质量可观测'],
  ],
  [2200, 1600, 5200]
));

const doc = new Document({
  styles: { default: { document: { run: { font: FONT, size: 21 } } } },
  numbering: {
    config: [
      { reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 480, hanging: 240 } } } }] },
      { reference: 'nums', levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 480, hanging: 300 } } } }] },
    ],
  },
  sections: [{
    properties: { page: { margin: { top: 1440, bottom: 1440, left: 1440, right: 1440 } } },
    children,
  }],
});

Packer.toBuffer(doc).then(buf => {
  require('fs').writeFileSync('D:/git/git_repo03/agent/docs/个人AI学习与求职助手_最佳设计方案_V3.docx', buf);
  console.log('OK, bytes:', buf.length);
});
