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
  router_accuracy: 0.85,
  router_macro_f1: 0.80,
  router_facet_exact_match: 0.85,
  router_facet_macro_f1: 0.80,
  router_in_scope_to_out_of_scope: 0.05,
};
const testset = JSON.parse(readFileSync(new URL('./rag_testset.json', import.meta.url), 'utf8'));
const routerSet = JSON.parse(readFileSync(new URL('./router_testset.json', import.meta.url), 'utf8'));
if (SMOKE) testset.cases = testset.cases.slice(0, 10);

// 瞬时故障重试：全量跑到第四个模式 (agentic) 时，它的第一条请求挂满 90 秒超时，
// 整个进程随之崩掉且不产出结果文件——而单独用全新 JVM 跑同一批 280 条 agentic
// 是 0 失败 (P50 4.1s / P95 5.6s)，所以是前 840 次请求留下的累积状态，不是这条 case 的问题。
// 累积状态的具体成因尚未定位 (见 docs/badcases.md Badcase 08)；此处先让评测能扛过
// 单次瞬时故障，不再把一次失败放大成"整轮无结果"。
// 4xx 是确定性错误 (请求本身不对) 立即抛出，只对 5xx 和超时做有限退避重试。
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

function normalizedFacets(value) {
  return [...new Set(Array.isArray(value) ? value.filter(v => typeof v === 'string').map(v => v.toLowerCase()) : [])]
    .sort();
}

function sameFacets(expected, actual) {
  return expected.length === actual.length && expected.every((facet, index) => facet === actual[index]);
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
// ============ 路由准确率 ============
let routerResult = null;
if (!process.argv.includes('--skip-router')) {
  let correct = 0;
  const errors = [];
  const calibrationSamples = [];
  let facetExactMatches = 0;
  const facetErrors = [];
  const labels = [...new Set(routerSet.cases.map(c => c.intent))].sort();
  const facetLabels = [...new Set(routerSet.cases.flatMap(c => normalizedFacets(c.retrieval_facets)))].sort();
  const facetCounts = Object.fromEntries(facetLabels.map(facet => [facet, { tp: 0, fp: 0, fn: 0, support: 0 }]));
  const confusion = Object.fromEntries(labels.map(expected => [expected, Object.fromEntries(labels.map(actual => [actual, 0]))]));
  for (const c of routerSet.cases) {
    const r = await post('/internal/route', { question: c.q });
    const isCorrect = r.intent === c.intent;
    if (isCorrect) correct++;
    else errors.push(`${c.q} → 期望${c.intent} 实际${r.intent}`);
    const confidence = Math.max(0, Math.min(1, Number(r.confidence) || 0));
    calibrationSamples.push({ confidence, correct: isCorrect, predicted: r.intent, expected: c.intent });
    const expectedFacets = normalizedFacets(c.retrieval_facets);
    const actualFacets = normalizedFacets(r.retrieval_facets);
    if (sameFacets(expectedFacets, actualFacets)) facetExactMatches++;
    else facetErrors.push(`${c.q} → 期望[${expectedFacets}] 实际[${actualFacets}]`);
    for (const facet of facetLabels) {
      const expected = expectedFacets.includes(facet);
      const actual = actualFacets.includes(facet);
      if (expected) facetCounts[facet].support++;
      if (expected && actual) facetCounts[facet].tp++;
      else if (actual) facetCounts[facet].fp++;
      else if (expected) facetCounts[facet].fn++;
    }
    if (!confusion[c.intent]) confusion[c.intent] = {};
    if (confusion[c.intent][r.intent] === undefined) confusion[c.intent][r.intent] = 0;
    confusion[c.intent][r.intent]++;
    process.stdout.write(`\r[router] ${correct + errors.length}/${routerSet.cases.length}`);
  }
  console.log();
  const byIntent = {};
  for (const label of labels) {
    const row = confusion[label] ?? {};
    const tp = row[label] ?? 0;
    const actual = Object.values(row).reduce((sum, value) => sum + value, 0);
    const predicted = labels.reduce((sum, expected) => sum + (confusion[expected]?.[label] ?? 0), 0);
    const precision = predicted ? tp / predicted : 0;
    const recall = actual ? tp / actual : 0;
    byIntent[label] = {
      precision,
      recall,
      f1: precision + recall ? (2 * precision * recall) / (precision + recall) : 0,
      support: actual,
    };
  }
  const macroF1 = avg(Object.values(byIntent).map(metric => metric.f1));
  const byFacet = Object.fromEntries(facetLabels.map(facet => {
    const counts = facetCounts[facet];
    const precision = counts.tp + counts.fp ? counts.tp / (counts.tp + counts.fp) : 0;
    const recall = counts.tp + counts.fn ? counts.tp / (counts.tp + counts.fn) : 0;
    return [facet, {
      precision,
      recall,
      f1: precision + recall ? (2 * precision * recall) / (precision + recall) : 0,
      support: counts.support,
    }];
  }));
  const facetMacroF1 = avg(Object.values(byFacet).map(metric => metric.f1));
  const inScopeLabels = labels.filter(label => label !== 'out_of_scope');
  const inScopeTotal = inScopeLabels.reduce((sum, label) => sum + Object.values(confusion[label] ?? {}).reduce((x, y) => x + y, 0), 0);
  const inScopeToOutOfScope = inScopeTotal
    ? inScopeLabels.reduce((sum, label) => sum + (confusion[label]?.out_of_scope ?? 0), 0) / inScopeTotal
    : 0;
  const calibrationBuckets = Array.from({ length: 10 }, (_, index) => ({
    range: `${(index / 10).toFixed(1)}-${((index + 1) / 10).toFixed(1)}`,
    count: 0,
    average_confidence: 0,
    accuracy: 0,
  }));
  for (const sample of calibrationSamples) {
    const index = Math.min(9, Math.floor(sample.confidence * 10));
    const bucket = calibrationBuckets[index];
    bucket.count++;
    bucket.average_confidence += sample.confidence;
    bucket.accuracy += sample.correct ? 1 : 0;
  }
  calibrationBuckets.forEach(bucket => {
    if (bucket.count) {
      bucket.average_confidence /= bucket.count;
      bucket.accuracy /= bucket.count;
    }
  });
  const brier = avg(calibrationSamples.map(sample =>
    (sample.confidence - (sample.correct ? 1 : 0)) ** 2));
  const ece = calibrationSamples.length
    ? calibrationBuckets.reduce((sum, bucket) => sum + bucket.count / calibrationSamples.length
      * Math.abs(bucket.average_confidence - bucket.accuracy), 0)
    : 0;
  const highConfidenceErrors = calibrationSamples.filter(sample =>
    sample.confidence >= 0.92 && !sample.correct).length;
  const highConfidenceCount = calibrationSamples.filter(sample => sample.confidence >= 0.92).length;
  const calibration = {
    brier,
    ece,
    high_confidence_error_rate: highConfidenceCount ? highConfidenceErrors / highConfidenceCount : 0,
    buckets: calibrationBuckets,
  };
  routerResult = {
    accuracy: correct / routerSet.cases.length,
    macro_f1: macroF1,
    facet_exact_match: facetExactMatches / routerSet.cases.length,
    facet_macro_f1: facetMacroF1,
    in_scope_to_out_of_scope: inScopeToOutOfScope,
    calibration,
    // 保留逐条样本，供离线校准训练使用；不包含原始问题文本。
    calibration_samples: calibrationSamples,
    by_intent: byIntent,
    by_facet: byFacet,
    confusion,
    errors,
    facet_errors: facetErrors,
  };
  console.log(`\n===== 路由准确率 ===== ${pct(routerResult.accuracy)} (${correct}/${routerSet.cases.length})`);
  console.log(`Macro-F1: ${routerResult.macro_f1.toFixed(3)} · 领域内误判越界: ${pct(routerResult.in_scope_to_out_of_scope)}`);
  console.log(`Facet Exact-Match: ${pct(routerResult.facet_exact_match)} · Facet Macro-F1: ${routerResult.facet_macro_f1.toFixed(3)}`);
  console.log(`置信度校准: Brier=${routerResult.calibration.brier.toFixed(3)} · ECE=${routerResult.calibration.ece.toFixed(3)} · 高置信错误=${pct(routerResult.calibration.high_confidence_error_rate)}`);
  console.log('混淆矩阵 (expected → predicted):', JSON.stringify(routerResult.confusion));
  errors.forEach(e => console.log('  错分: ' + e));

  if (routerResult.accuracy < THRESHOLDS.router_accuracy) {
    ciFailures.push(`router Accuracy ${pct(routerResult.accuracy)} < ${pct(THRESHOLDS.router_accuracy)}`);
  }
  if (routerResult.macro_f1 < THRESHOLDS.router_macro_f1) {
    ciFailures.push(`router Macro-F1 ${routerResult.macro_f1.toFixed(3)} < ${THRESHOLDS.router_macro_f1.toFixed(3)}`);
  }
  if (routerResult.facet_exact_match < THRESHOLDS.router_facet_exact_match) {
    ciFailures.push(`router facet exact-match ${pct(routerResult.facet_exact_match)} < ${pct(THRESHOLDS.router_facet_exact_match)}`);
  }
  if (routerResult.facet_macro_f1 < THRESHOLDS.router_facet_macro_f1) {
    ciFailures.push(`router facet Macro-F1 ${routerResult.facet_macro_f1.toFixed(3)} < ${THRESHOLDS.router_facet_macro_f1.toFixed(3)}`);
  }
  if (routerResult.in_scope_to_out_of_scope > THRESHOLDS.router_in_scope_to_out_of_scope) {
    ciFailures.push(`in-scope→out-of-scope ${pct(routerResult.in_scope_to_out_of_scope)} > ${pct(THRESHOLDS.router_in_scope_to_out_of_scope)}`);
  }
}

if (CI_MODE && ciFailures.length) {
  console.log('\n===== CI 阻断 =====');
  ciFailures.forEach(f => console.log('  ❌', f));
  process.exit(1);
} else if (ciFailures.length) {
  console.log('\n===== CI 警告 (未启用 --ci 模式, 仅提示) =====');
  ciFailures.forEach(f => console.log('  ⚠️ ', f));
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
  router: routerResult,
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
  // 平台自适应：WSL/Linux 下直接调 docker；Windows(cmd) 下经 wsl.exe 转发。
  // 统一用 stdin 传 SQL，避免跨 WSL/Windows 的文件重定向路径问题。
  const psql = 'docker exec -i tutor-postgres psql -U tutor -d tutor -q';
  if (process.platform === 'win32') {
    execSync(`wsl.exe -e bash -c "${psql}"`, { input: sql, shell: 'cmd.exe' });
  } else {
    execSync(psql, { input: sql, shell: '/bin/bash' });
  }
  console.log('eval_runs 已入库');
} catch (e) {
  console.log('eval_runs 入库失败(不影响结果文件): ' + e.message.slice(0, 120));
}
