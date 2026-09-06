#!/usr/bin/env bash
# 路由准确率复测: 30 条标注集打 /internal/route, 输出 accuracy / macro-F1 / 越界误判。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
node --input-type=module -e "
import { readFileSync } from 'node:fs';
const testset = JSON.parse(readFileSync('$ROOT/evals/router_testset.json', 'utf8'));
const norm = v => [...new Set((Array.isArray(v)?v:[]).filter(x=>typeof x==='string').map(x=>x.toLowerCase()))].sort();
const same = (a,b) => a.length===b.length && a.every((x,i)=>x===b[i]);
let correct = 0, oos = 0, inScopeTotal = 0, facetExact = 0;
const errors = [];
const labels = [...new Set(testset.cases.map(c=>c.intent))].sort();
const confusion = Object.fromEntries(labels.map(e=>[e,{}]));
for (const c of testset.cases) {
  const res = await fetch('http://127.0.0.1:8180/internal/route', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({question:c.q}), signal: AbortSignal.timeout(60000)});
  if (!res.ok) throw new Error('HTTP '+res.status);
  const r = await res.json();
  if (r.intent === c.intent) correct++; else errors.push(c.q+' 期望'+c.intent+' 实际'+r.intent);
  if (c.intent !== 'out_of_scope') { inScopeTotal++; if (r.intent==='out_of_scope') oos++; }
  if (same(norm(c.retrieval_facets), norm(r.retrieval_facets))) facetExact++;
  confusion[c.intent][r.intent] = (confusion[c.intent][r.intent]||0)+1;
  process.stdout.write('\r[router] '+(correct+errors.length)+'/'+testset.cases.length);
}
console.log();
const pct = x => (x*100).toFixed(1)+'%';
console.log('Accuracy: ' + pct(correct/testset.cases.length) + ' ('+correct+'/'+testset.cases.length+')');
console.log('Facet Exact-Match: ' + pct(facetExact/testset.cases.length));
console.log('领域内误判越界: ' + (inScopeTotal? pct(oos/inScopeTotal) : 'n/a'));
errors.forEach(e=>console.log('  错分: '+e));
console.log('confusion:', JSON.stringify(confusion));
"
