// 引用准确率(生成忠实度)评估 (实现设计 5.3): LLM-as-judge, 异源模型(Qwen评DeepSeek)。
// 方法: 取评估集12条问题走真实 /chat, 抽取回答中每个含[S#]的句子, judge判断"结论是否被所引证据支撑"。
// 局限(诚实声明): judge未经30条人工标注校准, 首跑结果作为基线; 校准计划见实现设计5.3。
// 用法: node evals/run_citation_eval.mjs
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const env = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);
const BASE = 'http://localhost:8180';
const JUDGE_MODELS = ['Qwen/Qwen2.5-32B-Instruct', 'Qwen/Qwen2.5-14B-Instruct', 'Qwen/Qwen3-14B'];

const testset = JSON.parse(readFileSync(new URL('./rag_testset.json', import.meta.url), 'utf8'));
const questions = [
  ...testset.cases.filter(c => c.type === 'single_hop_skill').slice(0, 8),
  ...testset.cases.filter(c => c.type === 'resource_rec').slice(0, 4),
].map(c => c.query);

// ---- 走真实 /chat, 解析SSE收集回答与引用 ----
async function chat(question) {
  const res = await fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ message: question }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`chat HTTP ${res.status}`);
  const text = await res.text();
  const citations = {};
  let answer = '';
  let event = null;
  for (const line of text.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) {
      try {
        const d = JSON.parse(line.slice(5));
        if (event === 'citation') citations[d.sid] = d.text;
        if (event === 'token') answer += d.text;
      } catch {}
    }
  }
  return { answer, citations };
}

// ---- 抽取引用句: 按句切分, 保留含[S#]的句子 ----
function citedClaims(answer) {
  const sentences = answer.split(/(?<=[。！？!?\n])/);
  const claims = [];
  for (const s of sentences) {
    const sids = [...s.matchAll(/\[S(\d)]/g)].map(m => 'S' + m[1]);
    if (sids.length) claims.push({ sentence: s.replace(/\s+/g, ' ').trim(), sids: [...new Set(sids)] });
  }
  return claims;
}

// ---- 异源 judge ----
let judgeModel = null;
async function judge(sentence, evidence) {
  const models = judgeModel ? [judgeModel] : JUDGE_MODELS;
  for (const model of models) {
    const res = await fetch(`${env.SILICONFLOW_BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.SILICONFLOW_API_KEY}` },
      body: JSON.stringify({
        model, temperature: 0, max_tokens: 200,
        response_format: { type: 'json_object' },
        messages: [
          { role: 'system', content: '你是引用忠实度裁判。判断「结论句」中的事实性内容是否被「证据」支撑。宽容度: 合理的同义转述算支撑; 证据完全没提到的具体事实算不支撑。输出JSON {"supported": true|false, "reason": "10字理由"}' },
          { role: 'user', content: `证据:\n${evidence}\n\n结论句:\n${sentence}` },
        ],
      }),
      signal: AbortSignal.timeout(60_000),
    });
    if (res.status === 400 || res.status === 404) continue; // 模型不存在, 试下一个
    if (!res.ok) throw new Error(`judge HTTP ${res.status}`);
    judgeModel = model;
    const out = JSON.parse((await res.json()).choices[0].message.content);
    return { supported: !!out.supported, reason: out.reason || '' };
  }
  throw new Error('无可用judge模型');
}

// ---- 主流程 ----
let total = 0, supported = 0;
const details = [];
for (let qi = 0; qi < questions.length; qi++) {
  const q = questions[qi];
  const { answer, citations } = await chat(q);
  const claims = citedClaims(answer);
  for (const c of claims) {
    const evidence = c.sids.map(sid => citations[sid]).filter(Boolean).join('\n');
    if (!evidence) continue;
    const v = await judge(c.sentence, evidence);
    total++;
    if (v.supported) supported++;
    else details.push({ q: q.slice(0, 30), claim: c.sentence.slice(0, 80), reason: v.reason });
    process.stdout.write(`\r问题 ${qi + 1}/${questions.length} 已判 ${total} 条 (支撑 ${supported})`);
  }
}
console.log();

const rate = total ? supported / total : 0;
console.log(`\n===== 引用准确率(忠实度) ===== ${(rate * 100).toFixed(1)}% (${supported}/${total})  judge=${judgeModel}`);
if (details.length) {
  console.log('不支撑样例:');
  details.slice(0, 6).forEach(d => console.log(`  [${d.q}] ${d.claim} → ${d.reason}`));
}

mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
writeFileSync(new URL(`./results/citation_${new Date().toISOString().replace(/[:.]/g, '-')}.json`, import.meta.url),
  JSON.stringify({ rate, total, supported, judge: judgeModel, questions: questions.length, details }, null, 2));
console.log('结果已写 evals/results/');
