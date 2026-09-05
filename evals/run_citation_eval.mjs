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
const BASE = process.env.CITATION_EVAL_BASE_URL || 'http://localhost:8180';
const JUDGE_MODELS = ['Qwen/Qwen2.5-32B-Instruct', 'Qwen/Qwen2.5-14B-Instruct', 'Qwen/Qwen3-14B'];

// /chat 是业务端点：需要 tutor_access cookie，且写请求要过 CSRF 双提交校验。
// 不带凭证时只会拿到 401/403，评测一条也判不了。
async function registerEvalUser() {
  const res = await fetch(`${BASE}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: `citation-eval-${Date.now()}@eval.local`,
      password: 'eval-pass-123',
      name: 'citation-eval',
    }),
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`register HTTP ${res.status}`);
  const jar = {};
  for (const raw of res.headers.getSetCookie?.() ?? []) {
    const [pair] = raw.split(';');
    const eq = pair.indexOf('=');
    if (eq > 0) jar[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
  }
  if (!jar.tutor_access || !jar.tutor_csrf) {
    throw new Error(`register 未返回预期 cookie: ${Object.keys(jar).join(',') || '(none)'}`);
  }
  return jar;
}

const jar = await registerEvalUser();
const AUTH_HEADERS = {
  Cookie: `tutor_access=${jar.tutor_access}; tutor_csrf=${jar.tutor_csrf}`,
  'X-CSRF-Token': jar.tutor_csrf,
};

const testset = JSON.parse(readFileSync(new URL('./rag_testset.json', import.meta.url), 'utf8'));
const questions = [
  ...testset.cases.filter(c => c.type === 'single_hop_skill').slice(0, 8),
  ...testset.cases.filter(c => c.type === 'resource_rec').slice(0, 4),
].map(c => c.query);

// ---- 走真实 /chat, 解析SSE收集回答与引用 ----
async function chat(question) {
  const res = await fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...AUTH_HEADERS },
    body: JSON.stringify({ message: question }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`chat HTTP ${res.status}`);
  const text = await res.text();
  const citations = {};
  let answer = '';
  let event = null;
  let turnError = null;
  for (const line of text.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) {
      try {
        const d = JSON.parse(line.slice(5));
        if (event === 'citation') citations[d.sid] = d.text;
        if (event === 'token') answer += d.text;
        // error 是 SSE 契约的一部分，HTTP 状态仍是 200。不识别它会把失败的回合
        // 当成"零引用句"静默跳过，最终得出一个基于空样本的准确率。
        if (event === 'error') turnError = d.code || d.message || 'unknown';
      } catch {}
    }
  }
  return { answer, citations, turnError };
}

// ---- 抽取引用句: 按句切分, 保留含[S#]的句子 ----
// 模型两种写法都用: 「正文[S1]。」和「正文。[S1]」。只按句末标点切分时后者会错位 ——
// 标记被留到下一句开头, 末尾的标记还会单独成句, 产出只有 [S1] 没有正文的空 claim;
// judge 收到空输入只能判"不支撑", 大量伪失败污染准确率 (首跑 51 条里有 15 条如此)。
// 因此改为整体匹配「一句正文 + 紧跟其后的引用标记」, 而不是 split ——
// split 的 lookbehind 里可选标记能匹配空, 句末位置依然会成为切点, 无法把标记留在前一句。
const CITED_SENTENCE = /[^。！？!?\n]*[。！？!?\n](?:[ \t]*\[S\d+])*|[^。！？!?\n]+$/g;

function citedClaims(answer) {
  const claims = [];
  for (const [sentence] of answer.matchAll(CITED_SENTENCE)) {
    const sids = [...sentence.matchAll(/\[S(\d+)]/g)].map(m => 'S' + m[1]);
    if (!sids.length) continue;
    // 去掉标记后必须还剩实质文字, 否则这条无法判定, 计入分母只会拉低准确率。
    const text = sentence.replace(/\s+/g, ' ').trim();
    if (!text.replace(/\[S\d+]/g, '').replace(/[\s。！？!?、,，:：]/g, '')) continue;
    claims.push({ sentence: text, sids: [...new Set(sids)] });
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
const failedTurns = [];
for (let qi = 0; qi < questions.length; qi++) {
  const q = questions[qi];
  const { answer, citations, turnError } = await chat(q);
  if (turnError) {
    failedTurns.push({ q: q.slice(0, 40), error: turnError });
    process.stdout.write(`\r问题 ${qi + 1}/${questions.length} 回合失败 (${turnError})\n`);
    continue;
  }
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
if (failedTurns.length) {
  console.log(`\n失败回合 ${failedTurns.length}/${questions.length} (未计入准确率):`);
  failedTurns.forEach(f => console.log(`  [${f.q}] → ${f.error}`));
}
if (details.length) {
  console.log('不支撑样例:');
  details.slice(0, 6).forEach(d => console.log(`  [${d.q}] ${d.claim} → ${d.reason}`));
}

mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
writeFileSync(new URL(`./results/citation_${new Date().toISOString().replace(/[:.]/g, '-')}.json`, import.meta.url),
  JSON.stringify({ rate, total, supported, judge: judgeModel, questions: questions.length,
    failed_turns: failedTurns, details }, null, 2));
console.log('结果已写 evals/results/');
// 全部回合失败时准确率会是 0/0=0，看着像"跑完了"。显式非零退出，避免误读为通过。
if (total === 0) {
  console.error('没有任何可判定的引用句 — 检查上面的失败回合');
  process.exit(1);
}
