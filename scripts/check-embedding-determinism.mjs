// 一次性验证：同一文本对 bge-m3 调两次 embedding，比对向量是否逐位一致。
// 目的：确认 embedding 确定性，判断能否把生成好的向量作为 CI fixture 提交进仓库。
// 用法: node scripts/check-embedding-determinism.mjs
import { readFileSync } from 'node:fs';

const env = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);

const SAMPLES = [
  'skill|RAG检索增强生成|难度:中级,约40小时|结合检索与生成的问答范式',
  'resource|LangChain入门教程|视频,中文,约6小时,难度:入门|覆盖链式调用与检索',
  '每天只有2小时，怎么安排学习大模型开发',
];

async function embed(texts) {
  const res = await fetch(`${env.SILICONFLOW_BASE_URL}/embeddings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.SILICONFLOW_API_KEY}` },
    body: JSON.stringify({ model: 'BAAI/bge-m3', input: texts }),
    signal: AbortSignal.timeout(60_000),
  });
  if (!res.ok) throw new Error(`embed HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).data.sort((a, b) => a.index - b.index).map(d => d.embedding);
}

function maxAbsDiff(a, b) {
  if (a.length !== b.length) return Infinity;
  let m = 0;
  for (let i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
  return m;
}

const runA = await embed(SAMPLES);
const runB = await embed(SAMPLES);

let allIdentical = true;
let worst = 0;
SAMPLES.forEach((_, i) => {
  const bitExact = JSON.stringify(runA[i]) === JSON.stringify(runB[i]);
  const diff = maxAbsDiff(runA[i], runB[i]);
  worst = Math.max(worst, diff);
  if (!bitExact) allIdentical = false;
  console.log(`sample[${i}] dim=${runA[i].length} bitExact=${bitExact} maxAbsDiff=${diff.toExponential(3)}`);
});

console.log('---');
if (allIdentical) {
  console.log('结论: 逐位一致 → 向量可作为 CI fixture 提交，检索门禁可零 token 复现。');
} else if (worst < 1e-5) {
  console.log(`结论: 非逐位一致但差异极小 (${worst.toExponential(3)}) → 提交向量可行，但 CI 比对需用容差而非精确相等。`);
} else {
  console.log(`结论: 差异显著 (${worst.toExponential(3)}) → 不确定性大，不宜提交向量当 fixture；Layer 2 需每次导种子 + 预算红线。`);
}
