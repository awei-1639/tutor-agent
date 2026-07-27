// RAG评估集生成 (实现设计 5.6): gold由图结构确定, LLM只改写问法——绕开"合成数据自证"陷阱。
// 用法: node evals/gen_testset.mjs  → evals/rag_testset.json (50条, 四类分层)
import { readFileSync, writeFileSync } from 'node:fs';

const env = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);
const load = f => JSON.parse(readFileSync(new URL(`../graph_data/${f}`, import.meta.url), 'utf8'));
const { skills } = load('seed_skills.json');
const { resources } = load('seed_resources.json');
const { jobs } = load('seed_jobs.json');
const skillById = new Map(skills.map(s => [s.id, s]));

// 确定性采样: 固定种子的伪随机, 保证评估集可复现
let seed = 42;
const rand = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const sample = (arr, n) => {
  const copy = [...arr], out = [];
  while (out.length < n && copy.length) out.push(copy.splice(Math.floor(rand() * copy.length), 1)[0]);
  return out;
};

const cases = [];

// 类型1: 单跳-技能概念 (15条) — gold=该技能节点
for (const s of sample(skills.filter(x => x.description.length > 20), 15)) {
  cases.push({
    type: 'single_hop_skill',
    seed_query: `围绕技能「${s.name}」(${s.description})提一个学习者会问的问题, 比如是什么/难不难/怎么入门`,
    gold: [s.id],
  });
}

// 类型2: 资源推荐 (15条) — gold=教同一技能且同形式的全部资源 (消除歧义)
const resPicks = sample(resources.filter(r => r.teaches.length > 0), 15);
for (const r of resPicks) {
  const skill = skillById.get(r.teaches[0]);
  const gold = resources
    .filter(x => x.teaches.includes(r.teaches[0]) && x.format === r.format)
    .map(x => x.id);
  cases.push({
    type: 'resource_rec',
    seed_query: `想找学习「${skill.name}」的${r.format}类资源(语言不限), 提一个自然的求推荐问题, 不要提及具体资源名`,
    gold,
  });
}

// 类型3: 岗位技能拆解 (10条) — gold=该岗位requires的全部技能
for (const j of sample(jobs.filter(x => x.requires.length >= 3), 10)) {
  cases.push({
    type: 'job_requirement',
    seed_query: `想了解「${j.title}」(公司: ${j.company})这类岗位需要掌握哪些技能, 提一个自然的问题, 需包含岗位名`,
    gold: j.requires,
  });
}

// 类型4: 多跳-前置链 (10条) — gold=直接前置+前置的前置 (两跳回溯, 图结构确定)
const withDeepPre = skills.filter(s =>
  s.prerequisites.length > 0 &&
  s.prerequisites.some(p => (skillById.get(p)?.prerequisites || []).length > 0));
for (const s of sample(withDeepPre, 10)) {
  const hop1 = s.prerequisites;
  const hop2 = hop1.flatMap(p => skillById.get(p)?.prerequisites || []);
  const gold = [...new Set([...hop1, ...hop2])];
  cases.push({
    type: 'multi_hop_prereq',
    seed_query: `零基础想最终学会「${s.name}」, 提一个询问需要先掌握哪些前置知识/学习顺序的问题`,
    gold,
  });
}

// LLM 批量改写问法 (每批10条; 只生成query文本, gold不经LLM)
async function paraphrase(batch) {
  const res = await fetch(`${env.DEEPSEEK_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.DEEPSEEK_API_KEY}` },
    body: JSON.stringify({
      model: 'deepseek-chat', temperature: 0.8, max_tokens: 3000,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: '你为检索评估集生成自然的中文用户提问。根据每条指令生成一个真实学习者口吻的问题(15-40字), 口语化、可含语气词, 三分之一的问题刻意不用指令中的原词而用同义表达。输出JSON {"queries":["问题1",...]}, 数量与指令条数一致。' },
        { role: 'user', content: batch.map((c, i) => `${i + 1}. ${c.seed_query}`).join('\n') },
      ],
    }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const out = JSON.parse((await res.json()).choices[0].message.content).queries;
  if (!Array.isArray(out) || out.length !== batch.length) throw new Error(`改写数量不符: ${out?.length}/${batch.length}`);
  return out;
}

console.log(`生成 ${cases.length} 条用例, LLM改写问法中...`);
for (let i = 0; i < cases.length; i += 10) {
  const batch = cases.slice(i, i + 10);
  const queries = await paraphrase(batch);
  batch.forEach((c, k) => { c.query = queries[k]; delete c.seed_query; });
  process.stdout.write(`\r改写 ${Math.min(i + 10, cases.length)}/${cases.length}`);
}
console.log();

const testset = {
  version: 1,
  created_at: new Date().toISOString(),
  note: 'gold由图结构确定(单跳=节点自身/资源=同技能同形式全部资源/岗位=requires/多跳=两跳前置), LLM仅改写问法',
  cases: cases.map((c, i) => ({ id: `q${String(i + 1).padStart(3, '0')}`, ...c })),
};
writeFileSync(new URL('./rag_testset.json', import.meta.url), JSON.stringify(testset, null, 2));
console.log(`DONE → evals/rag_testset.json (${cases.length}条: ` +
  Object.entries(cases.reduce((a, c) => (a[c.type] = (a[c.type] || 0) + 1, a), {}))
    .map(([k, v]) => `${k}=${v}`).join(', ') + ')');
