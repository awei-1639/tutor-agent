#!/usr/bin/env bash
# Phase 5 消融补充: agentic 模式在 multi_hop_prereq 切片上的表现。
# run_eval.mjs 的 agentic 抽样取测集前 50 条 (全是 single_hop), 无法回答"多跳通道是否值得保留"。
# 本脚本只对 80 条 multi_hop_prereq 跑 agentic (judge LLM 每跳一次), 输出可对比的指标。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
node --input-type=module -e "
import { readFileSync } from 'node:fs';
const testset = JSON.parse(readFileSync('$ROOT/evals/rag_testset.json', 'utf8'));
const cases = testset.cases.filter(c => c.type === 'multi_hop_prereq');
const perCase = [];
const latencies = [];
for (const c of cases) {
  let r;
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch('http://127.0.0.1:8180/internal/retrieve', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: c.query, topK: 5, mode: 'agentic' }),
        signal: AbortSignal.timeout(120_000),
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      r = await res.json();
      break;
    } catch (e) {
      if (attempt >= 2) throw e;
      console.error('retry after failure: ' + e.message);
      await new Promise(r => setTimeout(r, 8000));
    }
  }
  const got = r.results.map(x => x.node_id);
  const hitSet = c.gold.filter(g => got.includes(g));
  const recall = hitSet.length / c.gold.length;
  const firstRank = got.findIndex(id => c.gold.includes(id));
  perCase.push({ id: c.id, recall, rr: firstRank < 0 ? 0 : 1 / (firstRank + 1), hit: firstRank >= 0 });
  latencies.push(r.latency_ms);
  process.stdout.write('\r[agentic/multi_hop] ' + perCase.length + '/' + cases.length);
}
console.log();
const avg = a => a.reduce((x, y) => x + y, 0) / a.length;
const lat = [...latencies].sort((a, b) => a - b);
const pct = x => (x * 100).toFixed(1) + '%';
console.log('agentic on multi_hop_prereq (n=' + perCase.length + '):');
console.log('  Recall@5: ' + pct(avg(perCase.map(x => x.recall))));
console.log('  Hit@5:    ' + pct(avg(perCase.map(x => x.hit ? 1 : 0))));
console.log('  MRR:      ' + avg(perCase.map(x => x.rr)).toFixed(3));
console.log('  P50/P95:  ' + lat[Math.floor(lat.length/2)] + 'ms / ' + lat[Math.floor(lat.length*0.95)] + 'ms');
"
