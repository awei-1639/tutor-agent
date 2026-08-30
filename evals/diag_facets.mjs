// facet 误判诊断: 逐条对比 /internal/route 的 retrieval_facets 与 router_testset 标签
import { readFileSync } from 'node:fs';
const BASE = 'http://localhost:8180';
const set = JSON.parse(readFileSync(new URL('./router_testset.json', import.meta.url), 'utf8'));

for (const c of set.cases) {
  const res = await fetch(BASE + '/internal/route', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: c.q }),
  });
  if (!res.ok) { console.log('ERR', c.q, res.status); continue; }
  const d = await res.json();
  const got = JSON.stringify([...(d.retrieval_facets ?? [])].sort());
  const want = JSON.stringify([...(c.retrieval_facets ?? [])].sort());
  const mark = got === want ? 'OK  ' : 'MISS';
  if (got !== want) {
    console.log(`${mark} [${c.intent}] ${c.q}`);
    console.log(`      want=${want}  got=${got}  (intent_got=${d.intent}, conf=${d.confidence})`);
  }
}
console.log('done');
