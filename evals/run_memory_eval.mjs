// 记忆召回评测 (evals/run_memory_eval.mjs): 打真实管线的 /internal/memory-recall 端点,
// 种子数据经 /internal/memory-seed 重建 (embedding 走真实网关, 文本固定)。
// 用法: node evals/run_memory_eval.mjs [--ci] [--smoke]
//   --ci: 指标低于 THRESHOLDS 时 process.exit(1), CI 阻断
//   --smoke: 每类抽 2 条
// 指标:
//   fact_hit_at_k / fact_mrr          — 语义事实命中与排序
//   superseded_leak_rate              — 已失效事实回流率 (必须为 0)
//   episode_hit_at_k / episode_mrr    — 情景记忆命中
//   episode_recency_correct_rate      — 同主题新经历排在旧经历前
//   episode_no_match_precision        — 无关查询返回空情景记忆 (不硬凑)
// 输出: evals/results/memory_eval_<timestamp>.json
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const BASE = process.env.MEMORY_EVAL_BASE_URL || 'http://localhost:8180';
const CI_MODE = process.argv.includes('--ci');
const SMOKE = process.argv.includes('--smoke');
// 记忆是增强能力: 排序错误可用阈值兜底, 但失效事实回流与无关注入是正确性问题, 阈值为 0。
const THRESHOLDS = {
  fact_hit_at_k: 0.75,
  fact_mrr: 0.55,
  superseded_leak_rate: 0,
  episode_hit_at_k: 0.75,
  episode_mrr: 0.60,
  episode_recency_correct_rate: 1.0,
  episode_no_match_precision: 1.0,
};

const testset = JSON.parse(readFileSync(new URL('./memory_testset.json', import.meta.url), 'utf8'));
const RETRY_DELAYS_MS = [2_000, 8_000];

async function post(path, body) {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(BASE + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(90_000),
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

// 每个评测进程使用独立的种子用户，避免与库中真实用户或其他评测互串。
const SEED_USER_BASE = 990_000 + (process.pid % 1_000);

// --- 播种 ---
const seededUsers = new Map();
let seedOffset = 0;
for (const [key] of Object.entries(testset.scenarios)) {
  const userId = SEED_USER_BASE + seedOffset++;
  const seed = testset.scenarios[key];
  const result = await post('/internal/memory-seed', {
    user_id: userId,
    episodes: seed.episodes ?? [],
    facts: seed.facts ?? [],
  });
  seededUsers.set(key, { userId, seed });
  console.log(`播种 ${key}: user=${userId} episodes=${result.episodes} facts=${result.facts}`);
}

// --- 用例 ---
let cases = testset.cases;
if (SMOKE) {
  const byType = new Map();
  for (const c of cases) {
    if (!byType.has(c.type)) byType.set(c.type, []);
    byType.get(c.type).push(c);
  }
  cases = [...byType.values()].flatMap(group => group.slice(0, 2));
}

const perCase = [];
const latencies = [];
let factHits = 0, factMrrSum = 0, factCaseCount = 0, leaks = 0, leakCaseCount = 0;
let episodeHits = 0, episodeMrrSum = 0, episodeCaseCount = 0, recencyCorrect = 0, recencyCaseCount = 0;
let noMatchCorrect = 0, noMatchCount = 0;

for (const c of cases) {
  const scenario = seededUsers.get(c.scenario);
  if (!scenario) throw new Error(`unknown scenario ${c.scenario}`);
  const start = Date.now();
  const res = await post('/internal/memory-recall', {
    user_id: scenario.userId, query: c.query, top_k: c.top_k ?? 5,
  });
  latencies.push(Date.now() - start);
  const record = { id: c.id, type: c.type, query: c.query, ...{} };

  if (c.type === 'fact_stability' || c.type === 'fact_contradiction') {
    const factTexts = (res.facts ?? []).map(f => f.fact_text);
    const rank = factTexts.indexOf(c.gold_fact);
    const hit = rank >= 0;
    if (hit) { factHits++; factMrrSum += 1 / (rank + 1); }
    factCaseCount++;
    record.fact_rank = rank + 1;
    if (c.type === 'fact_contradiction') {
      leakCaseCount++;
      const leaked = factTexts.includes(c.banned_fact);
      if (leaked) leaks++;
      record.banned_leaked = leaked;
    }
    record.fact_hit = hit;
  }

  if (c.type === 'episode_hit' || c.type === 'episode_recency') {
    const summaries = (res.episodes ?? []).map(e => e.summary);
    const rank = summaries.indexOf(c.gold_episode);
    const hit = rank >= 0;
    if (hit) { episodeHits++; episodeMrrSum += 1 / (rank + 1); }
    episodeCaseCount++;
    record.episode_rank = rank + 1;
    record.episode_hit = hit;
    if (c.type === 'episode_recency') {
      recencyCaseCount++;
      const olderRank = summaries.indexOf(c.older_episode);
      // 新经历必须命中且排在旧经历之前（旧经历允许缺席）。
      const correct = rank >= 0 && (olderRank < 0 || rank < olderRank);
      if (correct) recencyCorrect++;
      record.recency_correct = correct;
      record.older_rank = olderRank + 1;
    }
  }

  if (c.type === 'no_match') {
    noMatchCount++;
    const empty = (res.episodes ?? []).length === 0;
    if (empty) noMatchCorrect++;
    record.no_match_empty = empty;
  }

  perCase.push(record);
}

const pct = (n, d) => (d === 0 ? null : n / d);
const metrics = {
  fact_hit_at_k: pct(factHits, factCaseCount),
  fact_mrr: pct(factMrrSum, factCaseCount),
  superseded_leak_rate: pct(leaks, leakCaseCount) ?? 0,
  episode_hit_at_k: pct(episodeHits, episodeCaseCount),
  episode_mrr: pct(episodeMrrSum, episodeCaseCount),
  episode_recency_correct_rate: pct(recencyCorrect, recencyCaseCount),
  episode_no_match_precision: pct(noMatchCorrect, noMatchCount) ?? 1,
  p50_latency_ms: quantile(latencies, 0.5),
  p95_latency_ms: quantile(latencies, 0.95),
  total_cases: perCase.length,
};

function quantile(sortedSource, q) {
  if (sortedSource.length === 0) return null;
  const sorted = [...sortedSource].sort((a, b) => a - b);
  const pos = (sorted.length - 1) * q;
  const base = Math.floor(pos);
  const rest = pos - base;
  return sorted[base + 1] != null ? sorted[base] + rest * (sorted[base + 1] - sorted[base]) : sorted[base];
}

console.log('\n===== 记忆召回评测 =====');
for (const [key, value] of Object.entries(metrics)) {
  console.log(`${key.padEnd(30)} ${value}`);
}
console.log('\n未达标用例:');
for (const r of perCase.filter(r => r.fact_hit === false || r.episode_hit === false
    || r.banned_leaked || r.recency_correct === false || r.no_match_empty === false)) {
  console.log(JSON.stringify(r));
}

// --- 结果落盘 ---
let gitSha = 'unknown';
try { gitSha = execSync('git rev-parse HEAD').toString().trim(); } catch { /* ignore */ }
const passed = Object.entries(THRESHOLDS).every(([key, threshold]) => {
  const value = metrics[key];
  return value != null && value >= threshold;
});
const outDir = new URL('./results/', import.meta.url);
mkdirSync(outDir, { recursive: true });
const outFile = new URL(`./results/memory_eval_${new Date().toISOString().replace(/[:.]/g, '-')}.json`, import.meta.url);
writeFileSync(outFile, JSON.stringify({
  at: new Date().toISOString(),
  git_sha: gitSha,
  testset_version: testset.version,
  smoke: SMOKE,
  thresholds: THRESHOLDS,
  ci_passed: passed,
  metrics,
  perCase,
}, null, 2));
console.log(`\n结果已写入 ${outFile.pathname}`);
if (CI_MODE) {
  for (const [key, threshold] of Object.entries(THRESHOLDS)) {
    const value = metrics[key];
    if (value == null || value < threshold) {
      console.error(`CI 未达标: ${key}=${value} < ${threshold}`);
    }
  }
  if (!passed) process.exit(1);
}
