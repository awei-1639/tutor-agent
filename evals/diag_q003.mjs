// q003 坏例诊断: 对比 vector_only / fused 的 TopK 节点与分值
const BASE = 'http://localhost:8180';
const QUERY = 'Linux里怎么快速查找包含某个关键词的文件呢?';

async function retrieve(mode, topK) {
  const res = await fetch(BASE + '/internal/retrieve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: QUERY, topK, mode }),
  });
  if (!res.ok) throw new Error(mode + ' HTTP ' + res.status + ' ' + (await res.text()).slice(0, 200));
  return res.json();
}

for (const mode of ['vector_only', 'fused', 'fused_rerank']) {
  const d = await retrieve(mode, 10);
  console.log('== ' + mode);
  for (const r of d.results ?? []) {
    console.log(' ', r.score?.toFixed?.(4) ?? r.score, r.node_id, r.type ?? '');
  }
}
