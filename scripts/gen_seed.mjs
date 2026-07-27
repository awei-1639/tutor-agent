// 种子数据生成: DeepSeek 分批生成 技能/资源/岗位, sanitize后写入 graph_data/
// 用法: node scripts/gen_seed.mjs
// 容错架构: LLM输出一律不可信 —— 可归一化的归一化, 修不了的丢单条并记录, 单条数据永不炸全局。
// 设计依据: V3方案 5.1 节点属性Schema; 边方向语义见 experiments/README.md Spike3
import { readFileSync, writeFileSync } from 'node:fs';

const env = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);

const DIFFICULTY = ['入门', '进阶', '高级'];
const FORMAT = ['视频课', '书籍', '文档教程', '实战项目', '题库'];
const LANGUAGE = ['中文', '英文', '中英字幕'];
const CITIES = ['杭州', '北京', '上海', '深圳', '广州', '成都', '远程'];
const dropped = []; // 全局丢弃清单, 最后汇报

async function llm(system, user, maxTokens = 8000) {
  const res = await fetch(`${env.DEEPSEEK_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.DEEPSEEK_API_KEY}` },
    body: JSON.stringify({
      model: 'deepseek-chat', temperature: 0.5, max_tokens: maxTokens,
      response_format: { type: 'json_object' },
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
    }),
    signal: AbortSignal.timeout(180_000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return JSON.parse((await res.json()).choices[0].message.content);
}

// 只对"整体性失败"重试: HTTP错误/解析失败/数组缺失或过少
async function llmBatch(system, user, arrayKey, label) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const data = await llm(system, user);
      const arr = data[arrayKey];
      if (Array.isArray(arr) && arr.length >= 5) return arr;
      throw new Error(`${arrayKey} 数组缺失或过少(${arr?.length ?? 'none'})`);
    } catch (e) {
      if (attempt === 3) throw new Error(`${label} 三次失败: ${e.message.slice(0, 150)}`);
      console.log(`[retry ${attempt}] ${label}: ${e.message.slice(0, 100)}`);
    }
  }
}

const normId = (id, prefix) => {
  let s = String(id || '').toLowerCase().trim().replace(/\s+/g, '-').replace(/[^a-z0-9:-]/g, '-');
  if (!s.startsWith(prefix + ':')) s = s.includes(':') ? prefix + ':' + s.split(':').pop() : prefix + ':' + s;
  s = prefix + ':' + s.slice(prefix.length + 1).replace(/^-+|-+$/g, '').replace(/-{2,}/g, '-');
  return s;
};
const coerce = (v, allowed, dflt) => allowed.includes(v) ? v : dflt;
const posInt = (v, dflt) => (Number.isFinite(+v) && +v > 0) ? Math.round(+v) : dflt;

// ============ 1. 技能 (3批串行, 后批可引用前批id) ============
const SKILL_SYS = `你是AI教育领域的知识图谱构建专家。输出严格的JSON。技能命名用业界通用中文名。
每个技能: {"id":"skill:英文kebab-case小写","name":"中文名","aliases":["2-4个别名含英文缩写"],"description":"30-50字客观描述","difficulty":"入门|进阶|高级","est_hours":整数学习小时,"prerequisites":["前置技能id"],"advances_to":["进阶方向技能id"]}
prerequisites/advances_to 只能引用本批或已有id列表中的id, 基础技能可为空数组。`;

const skillBatches = [
  ['基础与机器学习', 35, '覆盖: 编程基础(Python/SQL/Git/Linux/数据结构算法)、数学基础(线性代数/概率统计/微积分)、机器学习核心(监督/无监督/特征工程/模型评估/集成学习/XGBoost等)、数据处理(Pandas/NumPy/数据可视化)'],
  ['深度学习与大模型', 35, '覆盖: 深度学习基础(神经网络/CNN/RNN/优化器)、框架(PyTorch/TensorFlow)、NLP(词向量/文本分类/NER/Transformer/BERT/GPT)、大模型方向(Prompt工程/RAG/微调LoRA/Agent开发/LangChain/向量数据库)、CV基础(图像分类/目标检测)'],
  ['工程化与求职周边', 30, '覆盖: 模型部署(Docker/FastAPI/ONNX/模型量化)、MLOps基础、数据工程(Spark/数据仓库基础)、后端基础(Java/Spring Boot/微服务基础/Redis/消息队列)、软技能(简历撰写/技术面试/系统设计面试)'],
];

console.log('=== 生成技能 ===');
const allSkills = [];
const skillIds = new Set();
for (const [domain, count, scope] of skillBatches) {
  const arr = await llmBatch(SKILL_SYS,
    `生成${count}个「${domain}」领域的技能节点。${scope}。\n已有技能id(可引用, 不要重复生成): ${JSON.stringify([...skillIds])}\n输出: {"skills":[...]}`,
    'skills', `skills:${domain}`);
  const batchKept = [];
  for (const s of arr) {
    s.id = normId(s.id, 'skill');
    if (!/^skill:[a-z0-9-]{2,}$/.test(s.id)) { dropped.push(`skill(${s.id}):id无法归一化`); continue; }
    if (skillIds.has(s.id)) { dropped.push(`skill(${s.id}):重复`); continue; }
    if (!s.name) { dropped.push(`skill(${s.id}):缺name`); continue; }
    s.aliases = (Array.isArray(s.aliases) ? s.aliases : []).filter(Boolean).map(String).slice(0, 4);
    s.description = String(s.description || s.name);
    s.difficulty = coerce(s.difficulty, DIFFICULTY, '进阶');
    s.est_hours = posInt(s.est_hours, 40);
    skillIds.add(s.id);
    batchKept.push(s);
  }
  allSkills.push(...batchKept);
  console.log(`  ${domain}: +${batchKept.length} (累计 ${allSkills.length})`);
}
// 引用过滤放在全部技能确定之后 (跨批引用合法)
for (const s of allSkills) {
  s.prerequisites = (s.prerequisites || []).map(x => normId(x, 'skill')).filter(x => skillIds.has(x) && x !== s.id);
  s.advances_to = (s.advances_to || []).map(x => normId(x, 'skill')).filter(x => skillIds.has(x) && x !== s.id);
}

// ============ 并发工具 ============
async function pLimit(tasks, limit) {
  const results = [];
  let i = 0;
  await Promise.all(Array.from({ length: limit }, async () => {
    while (i < tasks.length) { const idx = i++; results[idx] = await tasks[idx](); }
  }));
  return results;
}

// ============ 2. 资源 (8批×25 并发4) ============
const RES_SYS = `你是AI学习资源专家。只推荐真实存在的知名学习资源。输出严格的JSON。
每个资源: {"id":"res:英文kebab-case小写","title":"资源名","description":"30-50字介绍","format":"视频课|书籍|文档教程|实战项目|题库","language":"中文|英文|中英字幕","duration_hours":整数,"difficulty":"入门|进阶|高级","url":null,"teaches":["技能id,1-3个"]}
重要: url一律null(禁止编造); teaches只能引用给定技能id; 资源必须真实知名(如CS224n、动手学深度学习、西瓜书、统计学习方法、LeetCode、fast.ai、HuggingFace课程、DataWhale教程等)。`;

const RES_TOPICS = [
  'Python与编程基础', '数学基础与机器学习理论', '机器学习实战与竞赛', '深度学习入门',
  'PyTorch与框架实战', 'NLP与Transformer', '大模型应用开发(RAG/Agent/微调)', '工程部署与面试准备',
];

console.log('=== 生成资源 (8批并发) ===');
const resIds = new Set();
const allResources = [];
const resBatches = await pLimit(RES_TOPICS.map(topic => async () => {
  const arr = await llmBatch(RES_SYS,
    `生成25个「${topic}」主题的真实学习资源。\n可引用的技能id: ${JSON.stringify([...skillIds])}\n输出: {"resources":[...]}`,
    'resources', `res:${topic}`);
  const kept = [];
  for (const r of arr) {
    r.id = normId(r.id, 'res');
    if (!/^res:[a-z0-9-]{2,}$/.test(r.id)) { dropped.push(`res(${r.id}):id无法归一化`); continue; }
    if (!r.title) { dropped.push(`res(${r.id}):缺title`); continue; }
    r.teaches = (Array.isArray(r.teaches) ? r.teaches : []).map(x => normId(x, 'skill')).filter(x => skillIds.has(x)).slice(0, 3);
    if (r.teaches.length === 0) { dropped.push(`res(${r.id}):无有效teaches`); continue; }
    r.description = String(r.description || r.title);
    r.format = coerce(r.format, FORMAT, '文档教程');
    r.language = coerce(r.language, LANGUAGE, '中文');
    r.difficulty = coerce(r.difficulty, DIFFICULTY, '进阶');
    r.duration_hours = posInt(r.duration_hours, 20);
    r.url = null; // 一律不信任LLM给的链接
    kept.push(r);
  }
  console.log(`  ${topic}: 生成${arr.length} 保留${kept.length}`);
  return kept;
}), 4);
// 去重放在汇总时 (并发批次间不共享可变Set, 避免竞态)
for (const batch of resBatches) for (const r of batch) {
  if (resIds.has(r.id)) { dropped.push(`res(${r.id}):跨批重复`); continue; }
  resIds.add(r.id);
  allResources.push(r);
}

// ============ 3. 岗位 (8批×25 并发4) ============
const JOB_SYS = `你是招聘领域专家,生成贴近2026年中国就业市场的仿真岗位JD。输出严格的JSON。
每个岗位: {"id":"job:英文kebab-case小写-序号","title":"岗位名","company":"公司名","city":"杭州|北京|上海|深圳|广州|成都|远程","salary":"如20-35K","education":"大专|本科|硕士","requires":["技能id,3-6个"],"jd_snapshot":"80-150字JD正文,含职责与要求"}
requires只能引用给定技能id, 必须与岗位title强相关。`;

const JOB_TOPICS = [
  'NLP算法工程师', '大模型应用开发工程师', '机器学习算法工程师', '计算机视觉工程师',
  '数据分析师与数据工程师', 'AI产品相关与提示词工程师', 'Java后端开发工程师(含AI平台方向)', '算法实习生与校招岗',
];

console.log('=== 生成岗位 (8批并发) ===');
const jobIds = new Set();
const allJobs = [];
const jobBatches = await pLimit(JOB_TOPICS.map((topic, ti) => async () => {
  const arr = await llmBatch(JOB_SYS,
    `生成25个「${topic}」方向的仿真岗位, id序号段用${ti * 25 + 1}~${ti * 25 + 25}。城市、公司规模、薪资多样化。\n可引用的技能id: ${JSON.stringify([...skillIds])}\n输出: {"jobs":[...]}`,
    'jobs', `job:${topic}`);
  const kept = [];
  for (const j of arr) {
    j.id = normId(j.id, 'job');
    if (!/^job:[a-z0-9-]{2,}$/.test(j.id)) { dropped.push(`job(${j.id}):id无法归一化`); continue; }
    if (!j.title || !j.company) { dropped.push(`job(${j.id}):缺title/company`); continue; }
    j.requires = (Array.isArray(j.requires) ? j.requires : []).map(x => normId(x, 'skill')).filter(x => skillIds.has(x)).slice(0, 6);
    if (j.requires.length < 2) { dropped.push(`job(${j.id}):有效requires<2`); continue; }
    if (!j.jd_snapshot || j.jd_snapshot.length < 50) { dropped.push(`job(${j.id}):jd过短`); continue; }
    j.city = coerce(j.city, CITIES, '远程');
    j.salary = String(j.salary || '面议');
    j.education = coerce(j.education, ['大专', '本科', '硕士'], '本科');
    kept.push(j);
  }
  console.log(`  ${topic}: 生成${arr.length} 保留${kept.length}`);
  return kept;
}), 4);
for (const batch of jobBatches) for (const j of batch) {
  if (jobIds.has(j.id)) { dropped.push(`job(${j.id}):跨批重复`); continue; }
  jobIds.add(j.id);
  allJobs.push(j);
}

// ============ 写文件 ============
const stamp = { generated_by: 'deepseek-chat', generated_at: new Date().toISOString(), review_status: 'pending_human_review' };
writeFileSync(new URL('../graph_data/seed_skills.json', import.meta.url), JSON.stringify({ ...stamp, skills: allSkills }, null, 2));
writeFileSync(new URL('../graph_data/seed_resources.json', import.meta.url), JSON.stringify({ ...stamp, resources: allResources }, null, 2));
writeFileSync(new URL('../graph_data/seed_jobs.json', import.meta.url), JSON.stringify({ ...stamp, jobs: allJobs }, null, 2));
writeFileSync(new URL('../graph_data/gen_dropped.log', import.meta.url), dropped.join('\n'));
console.log(`DONE skills=${allSkills.length} resources=${allResources.length} jobs=${allJobs.length} dropped=${dropped.length} (明细见 graph_data/gen_dropped.log)`);
