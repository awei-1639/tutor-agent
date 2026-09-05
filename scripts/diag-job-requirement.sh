#!/usr/bin/env bash
# job_requirement badcase 探针: 对单条用例跑三种模式, 打印返回的 node_id 与命中情况。
set -euo pipefail
QUERY="${1:-腾讯做对话系统的NLP算法工程师，得会啥技能啊？}"
GOLD="skill:natural-language-processing skill:transformers skill:gpt skill:prompt-engineering skill:rag skill:langchain"
for MODE in vector_only fused fused_rerank; do
  echo "===== $MODE ====="
  curl --noproxy '*' -sS -X POST http://127.0.0.1:8180/internal/retrieve \
    -H 'Content-Type: application/json' \
    -d "{\"query\":\"$QUERY\",\"topK\":5,\"mode\":\"$MODE\"}" \
  | node -e "
let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{
  const r=JSON.parse(d);
  const gold='$GOLD'.split(' ');
  for (const x of r.results) {
    const hit=gold.includes(x.node_id)?' <== GOLD':'';
    console.log((x.node_id||x.id||'?')+'  score='+(x.score??'?')+'  '+(x.title||x.text||'').slice(0,60)+hit);
  }
  console.log('telemetry:', JSON.stringify(r.telemetry||{}));
});"
done
