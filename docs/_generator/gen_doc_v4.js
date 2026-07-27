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
  children: [new TextRun({ text: '个人AI学习与求职助手 演进设计方案（V4 无时限版）', font: FONT, size: 40, bold: true })],
  alignment: AlignmentType.CENTER, spacing: { before: 200, after: 160 },
}));
children.push(new Paragraph({
  children: [new TextRun({ text: '去掉时间盒后的再优化：以"达标退出条件"驱动的四阶段演进路线', font: FONT, size: 24, color: '595959' })],
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
}));

// ============ 一、约束与原则 ============
children.push(h1('一、重新定义约束与优化目标'));
children.push(h2('1.1 去掉时间盒后，什么变了、什么不变'));
children.push(table(
  ['维度', '变化', '说明'],
  [
    ['时间', '4周时间盒 → 阶段退出条件', '每个阶段用可量化的达标指标决定何时进入下一阶段，而非日历'],
    ['深度', '演示级 → 生产级', 'reranker常开、agentic多跳检索、教练闭环、引用校验等原先"知道但放弃"的优化全部纳入'],
    ['目标', '面试价值 → 面试价值+真实可用', '终态是自己每天真实使用的个人产品，真实使用数据反哺评估'],
    ['选型原则', '不变：按数据规模选型', '时间放开 ≠ 复杂度放开。组件重引入依然由规模阈值触发（见第四章），否则就是简历驱动开发'],
    ['交付原则', '不变：任何时刻可演示', '每阶段结束是一个完整可用的版本，防止无限期项目烂尾'],
  ],
  [1300, 3300, 4400]
));
children.push(h2('1.2 无时限场景的最大风险'));
children.push(p([r('不是技术风险，而是"永远做不完"。', { bold: true }), r('应对：① 每阶段有明确退出标准，达标即冻结进入下一阶段；② 每阶段启动前重新审视需求清单并砍一轮；③ Phase 1 结束后项目即具备完整求职演示能力，之后每一阶段都是增量，随时可以停在任何阶段而不损失已有价值。')]));

// ============ 二、总体架构 ============
children.push(h1('二、总体架构（基座不变，能力分层演进）'));
children.push(p('技术基座沿用V3收敛结论：应用框架 + Agent编排 + Neo4j + PostgreSQL(pgvector)，单机 docker compose。实现为双轨并行设计——Java主线（Spring Boot 3 + LangChain4j + 手写轻量编排）与Python线（FastAPI + LangGraph），架构决策语言无关，实现级差异见《核心实现设计》两版。演进不改基座，只在其上叠加能力层：'));
children.push(...code([
  'Phase 1  基础闭环     检索问答 + 多专家 + Mock推送（=V3全部范围）',
  'Phase 2  检索与图谱深度  混合检索/重排/多跳/社区摘要/图谱流水线',
  'Phase 3  教练闭环与记忆  三层记忆/计划追踪重规划/模拟面试/引用校验',
  'Phase 4  产品化与真实数据 多用户/可观测性/真实岗位源/在线评估',
]));

// ============ 三、四阶段演进 ============
children.push(h1('三、四阶段演进路线'));

children.push(h2('Phase 1：基础闭环（范围 = V3 方案全部）'));
children.push(p('内容不重复，见V3方案书。它仍是第一优先级：先有端到端可用的最小系统，再谈深化。'));
children.push(bullet('退出标准：', 'fused检索 Hit@5≥85% + MRR超纯向量基线 + 无切片比基线低5pp以上（口径修订2026-07-26，原Recall@5≥85%在多gold口径下数学不可达）；引用准确率>90%；路由准确率>90%；Mock推送全链路100%跑通；README含A/B对比报告'));

children.push(h2('Phase 2：检索与图谱深度（核心技术护城河）'));
children.push(h3('2.1 检索升级'));
children.push(bullet('混合检索：', 'bge-m3 同时输出稠密+稀疏向量，pgvector存稠密、PG全文索引承接稀疏侧，双路RRF融合，解决专有名词（如"LoRA"、"vLLM"）稠密检索易漏的问题'));
children.push(bullet('重排常开：', 'bge-reranker-v2-m3 从可选变为默认，本地CPU部署，延迟预算内换取引用准确率提升'));
children.push(bullet('Agentic 多跳检索：', '首轮召回后由LLM判断"证据是否足以回答"，不足则改写查询二次检索（上限3轮），专治"零基础到NLP工程师要学什么"这类需要逐层展开的问题'));
children.push(...code([
  'def agentic_retrieve(query, max_hops=3):',
  '    evidence, q = [], query',
  '    for hop in range(max_hops):',
  '        evidence += retrieve(q, top_k=5)          # Phase1 fused管线',
  '        verdict = llm_judge(query, evidence)       # 证据充分性判断',
  '        if verdict.sufficient: break',
  '        q = verdict.followup_query                 # 缺口驱动的改写查询',
  '    return dedup(evidence)',
]));
children.push(bullet('社区摘要（GraphRAG式）：', '对图谱跑Leiden社区检测，LLM为每个社区生成摘要并向量化。全局性问题（"AI岗位技能版图长什么样"）检索社区摘要而非零散节点'));
children.push(h3('2.2 图谱构建流水线（从人工种子到自动化）'));
children.push(bullet('流水线：', 'LLM三元组抽取 → 实体对齐消歧（"深度学习"/"DL"归一）→ 置信度评分 → 低置信进审核队列 → 简易审核页一键批准/驳回 → 入图'));
children.push(bullet('规模目标：', '技能节点100 → 1000+，资源200 → 2000+，全部经流水线而非手工'));
children.push(h3('2.3 评估升级'));
children.push(bullet('标注集：', '100 → 300条，按单跳/多跳/全局/岗位拆解分层，部分请他人交叉标注消除自证偏差'));
children.push(bullet('回归机制：', '评估脚本进CI，检索相关代码合并前自动跑，指标回退即阻断——把"感觉变好了"永久排除出流程'));
children.push(bullet('退出标准：', 'Hit@5≥92%；MRR≥0.75；multi_hop切片Recall@5≥40%；引用准确率≥95%；图谱节点≥1000且流水线自动化率≥80%（口径同步修订2026-07-26）'));

children.push(h2('Phase 3：教练闭环与记忆（从"问答工具"到"教练"）'));
children.push(p([r('这是产品本质的跃迁：', { bold: true }), r('聊天机器人回答问题，教练跟踪你有没有做、做得怎么样、下一步该调整什么。')]));
children.push(h3('3.1 三层记忆体系'));
children.push(table(
  ['层', '载体', '内容与用途'],
  [
    ['工作记忆', 'conversations/messages表+滚动摘要', '当前会话历史的构造与注入，Phase 1已有（实现见《核心实现设计》2.1；编排状态持久化是另一件事，由checkpointer/trace表承担）'],
    ['情景记忆', '对话摘要向量化入pgvector', '跨会话检索"我们上周聊过什么"；每次会话结束自动生成摘要'],
    ['语义记忆', '画像（PG，含置信度）', '用户长期事实与偏好，Phase 1已有（含衰减与关键字段确认）；此阶段与情景记忆联动，增强主动复核'],
  ],
  [1500, 2700, 4800]
));
children.push(h3('3.2 学习计划执行闭环（教练核心）'));
children.push(...code([
  '周计划生成 → 每日任务卡片 → 用户打卡/跳过 → 完成度统计',
  '     ↑                                        ↓',
  '     └────── 重规划触发（周完成率<60% 或 用户反馈过难/过易）',
]));
children.push(bullet('数据结构：', 'plans / tasks / checkins 三张表；重规划时规划专家读取完成度历史，输出调整理由（降低强度/更换资源形式/顺延）'));
children.push(bullet('推送联动：', '每日推送从"岗位推荐"扩展为"今日任务+催办+岗位"，真正形成使用习惯'));
children.push(h3('3.3 多轮模拟面试'));
children.push(bullet('面试官Agent状态机：', '开场 → 逐题提问 → 根据回答质量决定追问或下一题 → 结束生成复盘报告（逐题评分、亮点、改进点、关联学习资源）'));
children.push(bullet('题库来源：', '图谱岗位requires技能 × 用户薄弱项（打卡数据）定向出题，而非泛泛题库'));
children.push(h3('3.4 回答质量护栏'));
children.push(bullet('引用校验（Self-RAG式）：', 'critic节点校验回答中每条结论是否被引用支撑，不支撑则删除该结论或触发重生成——引用准确率从"评估指标"变成"运行时保障"'));
children.push(bullet('反馈回流：', '每条回答带赞/踩，踩+纠错文本自动生成候选评估用例，人工确认后入标注集'));
children.push(bullet('退出标准：', '计划闭环全流程可用且自己真实使用2周不弃用；模拟面试完整跑通并产出复盘报告；运行时引用校验拦截率可观测'));

children.push(h2('Phase 4：产品化与真实数据（可选，走到这一步才谈"用户"）'));
children.push(bullet('多用户：', 'JWT认证 + 每用户token配额与速率限制；数据隔离靠user_id行级过滤，仍不做多租户架构'));
children.push(bullet('可观测性：', 'Langfuse自托管：全链路trace、token成本看板、各节点延迟分布——排查"为什么这次回答又贵又慢"不再靠猜'));
children.push(bullet('真实岗位源：', '合规渠道优先+受控抓取，增量去重（JD指纹），结构化抽取进流水线；Mock源保留为回落'));
children.push(bullet('在线评估：', '真实使用的赞/踩率、澄清触发率、推送打开率进看板，与离线指标互相校验'));
children.push(bullet('退出标准：', '3~5名真实用户连续使用1个月；对话首token P95 < 3s（验收线；优化目标1.5s，路径见实现设计6.4）；单用户日成本 < ¥1（开发期整体预估<¥5/天见V3；降到¥1路径=缓存命中率提升+模型分级路由全量生效）'));

// ============ 四、组件重引入触发条件 ============
children.push(h1('四、组件重引入触发条件（ADR续篇）'));
children.push(p('时间放开后最容易犯的错误是把砍掉的组件加回来"充实简历"。以下阈值未触发前，引入即过度设计：'));
children.push(table(
  ['组件', '触发条件', '未触发前的替代'],
  [
    ['Redis', '真实并发用户 > 50，或出现跨进程任务分发需求', '进程内TTL缓存 + PG任务表'],
    ['专用向量库（Qdrant等）', '向量总量 > 100万，或pgvector P95 > 100ms', 'pgvector + HNSW'],
    ['消息队列（RabbitMQ等）', '推送/抓取任务出现明确的削峰或解耦需求', 'APScheduler + PG任务表重试'],
    ['微服务拆分', '开发者 > 3人且模块变更频率显著分化', '单体 + Python包边界'],
    ['K8s', '多机部署成为真实需求（基本等于商业化）', 'docker compose'],
    ['本地部署LLM', 'API成本 > ¥500/月，或数据合规要求原文不出域', 'DeepSeek API + PII脱敏'],
  ],
  [2100, 3500, 3400]
));

// ============ 五、风险 ============
children.push(h1('五、风险与应对'));
children.push(table(
  ['风险', '影响', '应对'],
  [
    ['无限期烂尾（最大风险）', '高', '阶段退出标准+每阶段可独立演示；Phase 1完成即具备完整求职价值，后续全是增量'],
    ['范围蔓延（时间放开的副作用）', '高', '每阶段启动前砍一轮需求；新想法一律进backlog，不插队当前阶段'],
    ['图谱流水线抽取质量差', '中', '实体对齐+置信度分层+人工审核队列；抽检机制常态化'],
    ['Agentic多跳检索成本/延迟膨胀', '中', '跳数上限3、证据判断用小模型、社区摘要缓存'],
    ['自建评估集自证偏差', '中', '交叉标注+线上反馈回流用例，离线在线指标互相校验'],
    ['LangGraph等框架API快速迭代', '低', '锁版本+薄封装层，升级集中在适配层'],
  ],
  [2900, 900, 5200]
));

// ============ 六、总结 ============
children.push(h1('六、总结：V3与V4的关系'));
children.push(num('V3（4周版）没有被推翻，而是原样成为V4的Phase 1——时间盒约束下的决策在无时限场景依然是正确的起点'));
children.push(num('时间放开改变的是深度（检索护城河、教练闭环、质量护栏），不是组件数量——选型依然由规模阈值驱动'));
children.push(num('管理方式从"日历驱动"变为"指标驱动"：每阶段用可量化退出标准防止漂移，任何阶段停下来都是一个完整产品'));

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
  require('fs').writeFileSync('D:/git/git_repo03/agent/docs/个人AI学习与求职助手_演进设计方案_V4无时限版.docx', buf);
  console.log('OK, bytes:', buf.length);
});
