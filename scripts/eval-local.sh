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
command -v node >/dev/null 2>&1 || die "node 不可用；评测脚本是 Node.js，请在本 shell 中安装 Node 20+ (WSL 内需单独安装，不会自动复用 Windows 的 node)"
command -v java >/dev/null 2>&1 || die "java 不可用；需要 Java 21 运行后端 jar"
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

# ---- 2. 起后端 (Flyway 建表；种子依赖 kg_chunks 表存在，故必须先起后端) ----
[ -f "$JAR" ] || die "后端 jar 不存在，先在 backend/ 跑 mvn -DskipTests package"
echo "==> 启动后端 (Flyway 迁移建表)"
set -a
# shellcheck disable=SC1091
. "$ROOT/.env"
set +a
export INTERNAL_ENDPOINTS_ENABLED=true
# 本地评测：node 可能是 Windows 侧的，跨 WSL2 边界访问后端时来源地址非 127.0.0.1，
# 会被 /internal 的 loopback 检查挡成 404。评测是纯本地开发工具，显式放开。
export INTERNAL_ENDPOINTS_LOOPBACK_ONLY=false
export JWT_SECRET="${JWT_SECRET:-agent-local-eval-secret-32-bytes-minimum-2026}"
java -jar "$JAR" >"$BACKEND_LOG" 2>&1 &
BACKEND_PID="$!"

ready=0
for _ in $(seq 1 45); do
  # 后端进程若已退出 (如 Flyway 校验失败)，立即报错而不是空等 90 秒。
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "后端启动进程已退出，日志尾部:"; tail -n 40 "$BACKEND_LOG"; exit 1
  fi
  code=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:8180/readyz 2>/dev/null || true)
  [ "$code" = "200" ] && { ready=1; break; }
  sleep 2
done
[ "$ready" -eq 1 ] || { echo "后端未就绪，日志尾部:"; tail -n 60 "$BACKEND_LOG"; exit 1; }
echo "==> 后端就绪 (/readyz 200)"

# ---- 3. 幂等导种子 (最烧 Embedding token 的一步；此时 Flyway 已建好 kg_chunks) ----
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

# ---- 4. 跑评测 ----
# 跑之前记录已有的最新结果文件，用于跑完后判断"本次是否真的产出了新结果"。
baseline_before=$(ls -1t "$ROOT"/evals/results/eval_*.json 2>/dev/null | head -1)

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

# ---- 5. 与上一次基线对比 (仅当本次真的产出了新的检索结果文件) ----
if [ "$MODE" != "citation" ]; then
  cur=$(ls -1t "$ROOT"/evals/results/eval_*.json 2>/dev/null | head -1)
  if [ -z "$cur" ] || [ "$cur" = "$baseline_before" ]; then
    echo "==> 本次未产出新的检索结果文件，跳过基线对比 (评测可能失败)"
  elif [ -z "$baseline_before" ]; then
    echo "==> 首次结果，无历史基线可对比 (下次运行即可 diff)"
  else
    prev="$baseline_before"
    echo ""
    echo "==> 与上次基线对比 (fused_rerank): $(basename "$prev") → $(basename "$cur")"
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
  fi
fi
echo "==> 完成"

