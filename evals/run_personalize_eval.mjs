// 画像感知重排 A/B 评测: 打真实 /internal/retrieve 管线, 复用生产 ProfilePersonalizer (零复制)。
// 用法: node evals/run_personalize_eval.mjs [--with-agentic] [--limit N]
//
// 四个臂, 拆开"候选池变大"与"画像重排"两个变量:
//   A fused                       生产基线 (top5 池)
//   B fused_personalized + 空画像  溢出池(12)但纯裁剪 → 只测池效应
//   C fused_personalized + 对齐画像 溢出池 + 画像重排 → B vs C 是纯画像效应
//   D fused_personalized + 错位画像 画像与查询无关(轮转) → 安全性: 不相关画像不伤基线
//
// 画像来自查询文本本身的词元 (用户问自己熟悉的领域), 不碰 gold, 避免循环论证。
// 已知局限: 对齐臂测的是"话题对齐用户"群体下的机制收益, 不是全量用户的平均收益。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execSync } from 'node:child_process';

const BASE = 'http://localhost:8180';
const WITH_AGENTIC = process.argv.includes('--with-agentic');
const limitIdx = process.argv.indexOf('--limit');
const LIMIT = limitIdx > 0 ? Number(process.argv[limitIdx + 1]) : Infinity;
const TOP_K = 5;

const testset = JSON.parse(readFileSync(new URL('./rag_testset.json', import.meta.url), 'utf8'));
let cases = testset.cases;
if (Number.isFinite(LIMIT)) cases = cases.slice(0, LIMIT);

const STOPWORDS = new Set(['什么', '如何', '哪些', '怎么', '需要', '推荐', '学习', '教程', '资料',
  '材料', '适合', '入门', '基础', '资源', '区别', '意思', '问题', '解决', '快速', '查找',
  '包含', '关键词', '文件', '一个', '为啥', '用到', '明白', '理解', '到底', '里的', '出现']);

// 与后端 seedAliases 同思路: 中英边界切开, 停用词过滤, 取前 4 个词元作对齐画像
function topicTokens(query) {
  const mixed = query.toLowerCase()
    .replace(/(?<=[\u4e00-\u9fa5])(?=[a-z0-9])/g, ' ')
    .replace(/(?<=[a-z0-9])(?=[\u4e00-\u9fa5])/g, ' ');
  return [...new Set(mixed.split(/[^\p{L}\p{N}_-]+/u)
    .map(t => t.trim())
    .filter(t => t.length >= 2 && !STOPWORDS.has(t)))].slice(0, 4);
}

const alignedPersona = new Map();   // caseId -> persona
const allTokens = [];
for (const c of cases) {
  const tokens = topicTokens(c.query);
  allTokens.push(tokens);
  alignedPersona.set(c.id, tokens.length ? { skills: tokens.map(t => ({ name: t, confidence: 1.0 })) } : null);
}
const withPersona = cases.filter(c => alignedPersona.get(c.id));
console.log(`评测集: ${cases.length} 条 (有对齐画像: ${withPersona.length}, version=${testset.version})`);

const RETRY_DELAYS_MS = [2_000, 8_000];
async function post(path, body) {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(BASE + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body), signal: AbortSignal.timeout(90_000),
      });
      if (!res.ok) throw new Error(`${path} HTTP ${res.status}`);
      return res.json();
    } catch (error) {
      const clientError = /HTTP 4\d\d$/.test(error.message);
      if (clientError || attempt >= RETRY_DELAYS_MS.length) throw error;
      const delay = RETRY_DELAYS_MS[attempt];
      console.warn(`\n${path} 第 ${attempt + 1} 次失败 (${error.name}: ${error.message})，${delay}ms 后重试`);
      await new Promise(resolve => setTimeout(resolve, delay));
    }
  }
}

const avg = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
const pct = x => (x * 100).toFixed(1) + '%';

async function runArm(label, mode, personaOf) {
  const perCase = [];
  const latencies = [];
  for (const c of cases) {
    const persona = personaOf ? personaOf(c) : null;
    const r = await post('/internal/retrieve', { query: c.query, topK: TOP_K, mode, profile: persona });
    const got = r.results.map(x => x.node_id);
    const hitSet = c.gold.filter(g => got.includes(g));
    const firstRank = got.findIndex(id => c.gold.includes(id));
    perCase.push({ id: c.id, type: c.type, recall: hitSet.length / c.gold.length,
      rr: firstRank < 0 ? 0 : 1 / (firstRank + 1), hit: firstRank >= 0, retrieved: got });
    latencies.push(r.latency_ms);
    process.stdout.write(`\r[${label}] ${perCase.length}/${cases.length}`);
  }
  console.log();
  const agg = list => ({ n: list.length, recall_at_5: avg(list.map(x => x.recall)),
    hit_at_5: avg(list.map(x => x.hit ? 1 : 0)), mrr: avg(list.map(x => x.rr)) });
  const byType = {};
  for (const t of [...new Set(perCase.map(x => x.type))]) byType[t] = agg(perCase.filter(x => x.type === t));
  const lat = [...latencies].sort((a, b) => a - b);
  return { label, mode, overall: agg(perCase), by_type: byType,
    p50_ms: lat[Math.floor(lat.length * 0.5)], p95_ms: lat[Math.min(lat.length - 1, Math.floor(lat.length * 0.95))], perCase };
}

const armA = await runArm('A fused(基线)', 'fused', null);
const armB = await runArm('B 溢出池(空画像)', 'fused_personalized', () => null);
const armC = await runArm('C 溢出池+对齐画像', 'fused_personalized', c => alignedPersona.get(c.id));
// 错位画像: 取 137 条之外的 case 的词元, 保证与当前查询话题基本无关
const armD = await runArm('D 溢出池+错位画像', 'fused_personalized',
  c => alignedPersona.get(cases[(cases.indexOf(c) + 137) % cases.length].id));

// C vs B: 画像重排究竟改变了多少 case 的 top5
let orderChanged = 0, improved = 0, degraded = 0;
for (let i = 0; i < cases.length; i++) {
  const b = armB.perCase[i], c = armC.perCase[i];
  if (JSON.stringify(b.retrieved) !== JSON.stringify(c.retrieved)) orderChanged++;
  if (c.rr > b.rr) improved++;
  else if (c.rr < b.rr) degraded++;
}

console.log('\n===== 画像感知重排 A/B (Recall@5 / Hit@5 / MRR) =====');
for (const arm of [armA, armB, armC, armD]) {
  console.log(`${arm.label.padEnd(14)} ${pct(arm.overall.recall_at_5).padStart(6)} / ${pct(arm.overall.hit_at_5).padStart(6)} / ${arm.overall.mrr.toFixed(3)}   P50=${arm.p50_ms}ms P95=${arm.p95_ms}ms`);
}
console.log('----- C vs B (纯画像效应) 与 C vs D (对齐 vs 错位) -----');
console.log(`top5 顺序改变: ${orderChanged}/${cases.length}  C 优于 B: ${improved}  C 劣于 B: ${degraded}`);
console.log('----- 切片 Recall@5 (A → B → C → D) -----');
for (const t of Object.keys(armA.by_type)) {
  console.log(`${t.padEnd(20)} (n=${armA.by_type[t].n})  ${pct(armA.by_type[t].recall_at_5)} → ${pct(armB.by_type[t].recall_at_5)} → ${pct(armC.by_type[t].recall_at_5)} → ${pct(armD.by_type[t].recall_at_5)}`);
}

// 可选 agentic 臂 (多跳+judge LLM, 慢): 默认关闭
let agenticArms = null;
if (WITH_AGENTIC) {
  const slice = cases.slice(0, Math.min(30, cases.length));
  const saved = cases; cases = slice;
  const agA = await runArm('agentic(基线)', 'agentic', null);
  const agC = await runArm('agentic+对齐画像', 'agentic_personalized', c => alignedPersona.get(c.id));
  cases = saved;
  agenticArms = { slice: slice.length, baseline: { ...agA, perCase: undefined }, personalized: { ...agC, perCase: undefined } };
  console.log('\n===== agentic 臂 (前 30 条) =====');
  console.log(`基线      ${pct(agA.overall.recall_at_5)} / ${pct(agA.overall.hit_at_5)} / ${agA.overall.mrr.toFixed(3)}`);
  console.log(`对齐画像  ${pct(agC.overall.recall_at_5)} / ${pct(agC.overall.hit_at_5)} / ${agC.overall.mrr.toFixed(3)}`);
}

let gitSha = null;
try { gitSha = execSync('git rev-parse HEAD', { cwd: new URL('..', import.meta.url) }).toString().trim(); } catch {}
const record = {
  at: new Date().toISOString(), git_sha: gitSha, testset_version: testset.version,
  design: 'A=基线top5池 B=溢出池空画像(池效应) C=溢出池+对齐画像 D=溢出池+错位画像(安全性)',
  fused_arms: { A: { ...armA, perCase: undefined }, B: { ...armB, perCase: undefined },
    C: { ...armC, perCase: undefined }, D: { ...armD, perCase: undefined } },
  c_vs_b: { orderChanged, improved, degraded }, agentic_arms: agenticArms,
  // 坏case: C 比 B 差的条目 (画像起了反作用的线索)
  c_regressions: armC.perCase
    .map((c, i) => ({ id: c.id, type: c.type, from: armB.perCase[i].rr, to: c.rr }))
    .filter(x => x.to < x.from)
    .map(x => `${x.id}:${x.type} rr ${x.from.toFixed(2)}→${x.to.toFixed(2)}`),
};
mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
const outfile = `results/personalize_${record.at.replace(/[:.]/g, '-')}.json`;
writeFileSync(new URL('./' + outfile, import.meta.url), JSON.stringify(record, null, 2));
console.log(`\n结果已写 evals/${outfile}`);
