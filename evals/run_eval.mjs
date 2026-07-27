// 评估执行 (实现设计 5.2/5.3/5.7): 打真实管线的 /internal 端点。
// 用法: node evals/run_eval.mjs [--skip-router] [--ci] [--smoke]
//   --ci:    指标低于 THRESHOLDS 时 process.exit(1), CI 阻断
//   --smoke: 抽样 10 条 (CI 提速)
// 输出: 总体+切片指标表, 结果追加写 evals/results/ 并入库 eval_runs。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execSync } from 'node:child_process';

const BASE = 'http://localhost:8180';
const CI_MODE = process.argv.includes('--ci');
const SMOKE = process.argv.includes('--smoke');
// Phase 2 退出标准 (V4 2.3): --ci 模式下任一不达标 exit 1
const THRESHOLDS = {
  hit_at_5: 0.85,
  recall_at_5_multi: 0.40,
  mrr_fused: 0.60,
};
const testset = JSON.parse(readFileSync(new URL('./rag_testset.json', import.meta.url), 'utf8'));
const routerSet = JSON.parse(readFileSync(new URL('./router_testset.json', import.meta.url), 'utf8'));
if (SMOKE) testset.cases = testset.cases.slice(0, 10);

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000),
  });
  if (!res.ok) throw new Error(`${path} HTTP ${res.status}`);
  return res.json();
}

function percentile(sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
}

// ============ RAG 检索 A/B ============
async function evalMode(mode) {
  // agentic 模式多跳+judge LLM 全量耗时太长, 抽样 50 条减少 CI 时间
  const cases = mode === 'agentic' ? testset.cases.slice(0, 50) : testset.cases;
  const perCase = [];
  const latencies = [];
  for (const c of cases) {
    const r = await post('/internal/retrieve', { query: c.query, topK: 5, mode });
    const got = r.results.map(x => x.node_id);
    const hitSet = c.gold.filter(g => got.includes(g));
    const recall = hitSet.length / c.gold.length;
    const firstRank = got.findIndex(id => c.gold.includes(id));
    perCase.push({ id: c.id, type: c.type, recall, rr: firstRank < 0 ? 0 : 1 / (firstRank + 1), hit: firstRank >= 0 });
    latencies.push(r.latency_ms);
    process.stdout.write(`\r[${mode}] ${perCase.length}/${cases.length}`);
  }
  console.log();
  const lat = [...latencies].sort((a, b) => a - b);
  const agg = list => ({
    n: list.length,
    recall_at_5: avg(list.map(x => x.recall)),
    hit_at_5: avg(list.map(x => x.hit ? 1 : 0)),
    mrr: avg(list.map(x => x.rr)),
  });
  const byType = {};
  for (const t of [...new Set(perCase.map(x => x.type))]) {
    byType[t] = agg(perCase.filter(x => x.type === t));
  }
  return { mode, overall: agg(perCase), by_type: byType, p50_ms: percentile(lat, 0.5), p95_ms: percentile(lat, 0.95), perCase };
}
const avg = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
const pct = x => (x * 100).toFixed(1) + '%';

console.log(`RAG评估集: ${testset.cases.length}条${SMOKE ? ' (smoke)' : ''}`);
const vec = await evalMode('vector_only');
const fused = await evalMode('fused');
const rr = await evalMode('fused_rerank');
const agentic = await evalMode('agentic');

console.log('\n===== RAG检索 四方对比 (vector_only → fused → fused_rerank → agentic) =====');
console.log(`总体   Recall@5: ${pct(vec.overall.recall_at_5)} → ${pct(fused.overall.recall_at_5)} → ${pct(rr.overall.recall_at_5)} → ${pct(agentic.overall.recall_at_5)}`);
console.log(`       Hit@5:    ${pct(vec.overall.hit_at_5)} → ${pct(fused.overall.hit_at_5)} → ${pct(rr.overall.hit_at_5)} → ${pct(agentic.overall.hit_at_5)}`);
console.log(`       MRR:      ${vec.overall.mrr.toFixed(3)} → ${fused.overall.mrr.toFixed(3)} → ${rr.overall.mrr.toFixed(3)} → ${agentic.overall.mrr.toFixed(3)}`);
console.log(`延迟   P50: ${vec.p50_ms} → ${fused.p50_ms} → ${rr.p50_ms} → ${agentic.p50_ms}ms   P95: ${vec.p95_ms} → ${fused.p95_ms} → ${rr.p95_ms} → ${agentic.p95_ms}ms`);
console.log('----- 切片 (Recall@5) -----');
for (const t of Object.keys(fused.by_type)) {
  const a = agentic.by_type[t];
  const aStr = a ? `${pct(a.recall_at_5)}` : 'n/a';
  console.log(`${t.padEnd(18)} (n=${fused.by_type[t].n})  ${pct(vec.by_type[t].recall_at_5)} → ${pct(fused.by_type[t].recall_at_5)} → ${pct(rr.by_type[t].recall_at_5)} → ${aStr}`);
}

// ============ CI 阈值阻断 (V4 2.3) ============
let ciFailures = [];
const multi = rr.by_type['multi_hop_prereq'];
if (rr.overall.hit_at_5 < THRESHOLDS.hit_at_5) {
  ciFailures.push(`Hit@5 ${pct(rr.overall.hit_at_5)} < ${pct(THRESHOLDS.hit_at_5)}`);
}
if (multi && multi.recall_at_5 < THRESHOLDS.recall_at_5_multi) {
  ciFailures.push(`multi_hop Recall@5 ${pct(multi.recall_at_5)} < ${pct(THRESHOLDS.recall_at_5_multi)}`);
}
if (fused.overall.mrr < THRESHOLDS.mrr_fused) {
  ciFailures.push(`fused MRR ${fused.overall.mrr.toFixed(3)} < ${THRESHOLDS.mrr_fused}`);
}
if (CI_MODE && ciFailures.length) {
  console.log('\n===== CI 阻断 =====');
  ciFailures.forEach(f => console.log('  ❌', f));
  process.exit(1);
} else if (ciFailures.length) {
  console.log('\n===== CI 警告 (未启用 --ci 模式, 仅提示) =====');
  ciFailures.forEach(f => console.log('  ⚠️ ', f));
}

// ============ 路由准确率 ============
let routerResult = null;
if (!process.argv.includes('--skip-router')) {
  let correct = 0;
  const errors = [];
  for (const c of routerSet.cases) {
    const r = await post('/internal/route', { question: c.q });
    if (r.intent === c.intent) correct++;
    else errors.push(`${c.q} → 期望${c.intent} 实际${r.intent}`);
    process.stdout.write(`\r[router] ${correct + errors.length}/${routerSet.cases.length}`);
  }
  console.log();
  routerResult = { accuracy: correct / routerSet.cases.length, errors };
  console.log(`\n===== 路由准确率 ===== ${pct(routerResult.accuracy)} (${correct}/${routerSet.cases.length})`);
  errors.forEach(e => console.log('  错分: ' + e));
}

// ============ 留痕 (5.5): 结果文件 + eval_runs ============
let gitSha = null;
try { gitSha = execSync('git rev-parse HEAD', { cwd: new URL('..', import.meta.url) }).toString().trim(); } catch {}
const record = {
  at: new Date().toISOString(),
  git_sha: gitSha,
  testset_version: testset.version,
  thresholds: THRESHOLDS,
  ci_passed: CI_MODE ? ciFailures.length === 0 : null,
  vector_only: { ...vec, perCase: undefined },
  fused: { ...fused, perCase: undefined },
  fused_rerank: { ...rr, perCase: undefined },
  agentic: { ...agentic, perCase: undefined },
  router: routerResult ? { accuracy: routerResult.accuracy, errors: routerResult.errors } : null,
  // 坏case清单: 最终模式下recall<1的用例 (优化线索)
  fused_misses: rr.perCase.filter(x => x.recall < 1).map(x => x.id + ':' + x.type + ':' + x.recall.toFixed(2)),
};
mkdirSync(new URL('./results/', import.meta.url), { recursive: true });
const outfile = `results/eval_${record.at.replace(/[:.]/g, '-')}.json`;
writeFileSync(new URL('./' + outfile, import.meta.url), JSON.stringify(record, null, 2));
console.log(`\n结果已写 evals/${outfile}`);

// eval_runs 入库 (通过psql, 失败不阻塞)
try {
  const metrics = JSON.stringify({
    vector_only: record.vector_only, fused: record.fused, fused_rerank: record.fused_rerank, router: record.router,
  }).replace(/'/g, "''");
  const sql = `INSERT INTO eval_runs (git_sha, mode, model_config, metrics) VALUES ('${gitSha || ''}', 'ab_full', '{"embed":"bge-m3"}', '${metrics}');`;
  writeFileSync(new URL('./results/_last_insert.sql', import.meta.url), sql);
  execSync(`wsl.exe -e bash -c "docker exec -i tutor-postgres psql -U tutor -d tutor -q" < "${new URL('./results/_last_insert.sql', import.meta.url).pathname.slice(1)}"`, { shell: 'cmd.exe' });
  console.log('eval_runs 已入库');
} catch (e) {
  console.log('eval_runs 入库失败(不影响结果文件): ' + e.message.slice(0, 120));
}
