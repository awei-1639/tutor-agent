#!/usr/bin/env bash
# 本地一键 eval：起依赖 → (幂等)导种子 → 起后端 → 跑评测 → 与上次基线对比。
#
# 用法:
#   scripts/eval-local.sh [--retrieval|--citation|--all] [--skip-seed] [--force-seed] [--smoke]
#
# 成本分层 (重要):
#   --retrieval (默认)  只调 Embedding API，便宜。跑 run_eval.mjs。
#   --citation          额外调 DeepSeek 生成 + Qwen judge，贵。跑 run_citation_eval.mjs。
#   --all               两者都跑。
# 种子导入 (import_seed.mjs) 会调 Embedding API，是最烧 token 的一步；默认按 kg_chunks
# 行数判断是否已导入，已导入则跳过。--force-seed 强制重导，--skip-seed 永不导。
set -uo pipefail

MODE="retrieval"
SEED="auto"        # auto | skip | force
SMOKE_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --retrieval) MODE="retrieval" ;;
    --citation)  MODE="citation" ;;
    --all)       MODE="all" ;;
    --skip-seed) SEED="skip" ;;
    --force-seed) SEED="force" ;;
    --smoke)     SMOKE_FLAG="--smoke" ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
cd "$ROOT"
JAR="$ROOT/backend/target/personal-ai-tutor-0.1.0-SNAPSHOT.jar"
BACKEND_LOG="$(mktemp -t agent-eval-backend.XXXXXX.log)"
BACKEND_PID=""

cleanup() {
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && wait "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT

die() { echo "ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker 不可用；请在能访问 docker 的 shell (如 WSL) 中运行"
[ -f "$ROOT/.env" ] || die ".env 不存在；先 cp .env.example .env 并填入 Embedding/LLM key"

# ---- 1. 起 postgres + neo4j ----
echo "==> 启动依赖容器 postgres + neo4j"
docker compose --env-file "$ROOT/.env" up -d postgres neo4j >/dev/null 2>&1 \
  || docker start tutor-postgres tutor-neo4j >/dev/null 2>&1 \
  || die "无法启动 postgres/neo4j 容器"

# 等 postgres 就绪
for _ in $(seq 1 30); do
  docker exec tutor-postgres pg_isready -U tutor -d tutor >/dev/null 2>&1 && break
  sleep 2
done
docker exec tutor-postgres pg_isready -U tutor -d tutor >/dev/null 2>&1 \
  || die "postgres 未就绪"

# ---- 2. 幂等导种子 (最烧 Embedding token 的一步) ----
seed_needed() {
  local n
  n=$(docker exec tutor-postgres psql -U tutor -d tutor -tAc \
        "SELECT count(*) FROM kg_chunks" 2>/dev/null || echo 0)
  [ "${n:-0}" -eq 0 ]
}
if [ "$SEED" = "force" ] || { [ "$SEED" = "auto" ] && seed_needed; }; then
  echo "==> 导入种子 (会调用 Embedding API)"
  OUT_DIR="$(mktemp -d -t tutor-seed.XXXXXX)"
  export OUT_DIR
  node "$ROOT/scripts/import_seed.mjs" || die "种子生成失败"
  docker exec -i tutor-neo4j cypher-shell -u neo4j -p "${NEO4J_PASSWORD:-tutor_dev_only}" < "$OUT_DIR/seed.cypher" >/dev/null 2>&1 || true
  docker exec -i tutor-postgres psql -U tutor -d tutor -q < "$OUT_DIR/kg_chunks.sql" || die "kg_chunks 导入失败"
  [ -f "$OUT_DIR/jobs.sql" ] && docker exec -i tutor-postgres psql -U tutor -d tutor -q < "$OUT_DIR/jobs.sql" || true
else
  echo "==> 跳过种子导入 (kg_chunks 已有数据；--force-seed 可强制重导)"
fi

# ---- 3. 起后端 ----
[ -f "$JAR" ] || die "后端 jar 不存在，先在 backend/ 跑 mvn -DskipTests package"
echo "==> 启动后端"
set -a
# shellcheck disable=SC1091
. "$ROOT/.env"
set +a
export INTERNAL_ENDPOINTS_ENABLED=true
export JWT_SECRET="${JWT_SECRET:-agent-local-eval-secret-32-bytes-minimum-2026}"
java -jar "$JAR" >"$BACKEND_LOG" 2>&1 &
BACKEND_PID="$!"

ready=0
for _ in $(seq 1 45); do
  code=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:8180/readyz 2>/dev/null || true)
  [ "$code" = "200" ] && { ready=1; break; }
  sleep 2
done
[ "$ready" -eq 1 ] || { echo "后端未就绪，日志尾部:"; tail -n 60 "$BACKEND_LOG"; exit 1; }
echo "==> 后端就绪 (/readyz 200)"

# ---- 4. 跑评测 ----
run_retrieval() {
  echo "==> 检索评测 (run_eval.mjs)"
  node "$ROOT/evals/run_eval.mjs" $SMOKE_FLAG
}
run_citation() {
  echo "==> 引用忠实度评测 (run_citation_eval.mjs) — 消耗 DeepSeek + judge token"
  node "$ROOT/evals/run_citation_eval.mjs"
}
case "$MODE" in
  retrieval) run_retrieval ;;
  citation)  run_citation ;;
  all)       run_retrieval; run_citation ;;
esac

# ---- 5. 与上一次基线对比 (仅检索有结构化 results/) ----
if [ "$MODE" != "citation" ]; then
  latest_two=$(ls -1t "$ROOT"/evals/results/eval_*.json 2>/dev/null | head -2)
  count=$(printf '%s\n' "$latest_two" | grep -c .)
  if [ "$count" -eq 2 ]; then
    cur=$(printf '%s\n' "$latest_two" | sed -n '1p')
    prev=$(printf '%s\n' "$latest_two" | sed -n '2p')
    echo ""
    echo "==> 与上次基线对比 (fused_rerank)"
    node -e '
      const fs=require("fs");
      const [cur,prev]=process.argv.slice(1).map(p=>JSON.parse(fs.readFileSync(p,"utf8")));
      const pick=r=>r&&r.fused_rerank&&r.fused_rerank.overall||{};
      const c=pick(cur), p=pick(prev);
      const fmt=(a,b)=>`${(b*100).toFixed(1)}% → ${(a*100).toFixed(1)}% (${((a-b)*100>=0?"+":"")+((a-b)*100).toFixed(1)}pt)`;
      console.log("Hit@5   ", fmt(c.hit_at_5||0, p.hit_at_5||0));
      console.log("Recall@5", fmt(c.recall_at_5||0, p.recall_at_5||0));
      console.log("MRR     ", `${(p.mrr||0).toFixed(3)} → ${(c.mrr||0).toFixed(3)} (${((c.mrr-p.mrr)>=0?"+":"")+(c.mrr-p.mrr).toFixed(3)})`);
    ' "$cur" "$prev"
  else
    echo "==> 只有一次结果，无基线可对比 (下次运行即可 diff)"
  fi
fi
echo "==> 完成"

