#!/bin/bash
# 鲁棒性演练: 七场景自动判定, 输出 PASS/FAIL 摘要, 结束后环境自还原。
# 场景与通过标准对应 docs/badcases.md Badcase 11/12/13 及演练报告 (2026-10-25)。
#
# 用法 (WSL 内, 需 Docker 依赖与 .env 密钥):
#   bash scripts/robustness-drill.sh
# 退出码: 0=全部 PASS, 1=存在 FAIL, 2=环境不可用。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
declare -a SUMMARY
BACKEND_PID=""

report() { # report <PASS|FAIL> <场景> <说明>
  SUMMARY+=("$1|$2|$3")
  if [ "$1" = PASS ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  echo "[$1] $2 — $3"
}

cleanup() {
  [ -n "$BACKEND_PID" ] && { kill "$BACKEND_PID" 2>/dev/null; wait "$BACKEND_PID" 2>/dev/null; }
  docker update --restart no tutor-postgres tutor-neo4j >/dev/null 2>&1
  docker stop tutor-postgres tutor-neo4j >/dev/null 2>&1
}
trap cleanup EXIT

# ---------- 依赖容器 ----------
start_containers() {
  local st pg neo i
  st=$(docker inspect -f '{{.State.Status}}' tutor-postgres 2>/dev/null || echo missing)
  [ "$st" = running ] || docker start tutor-postgres tutor-neo4j >/dev/null 2>&1
  for i in $(seq 1 20); do
    pg=$(docker inspect -f '{{.State.Health.Status}}' tutor-postgres 2>/dev/null || echo bad)
    neo=$(docker inspect -f '{{.State.Health.Status}}' tutor-neo4j 2>/dev/null || echo bad)
    [ "$pg" = healthy ] && [ "$neo" = healthy ] && return 0
    sleep 3
  done
  return 1
}

start_containers || { echo "FATAL: 依赖容器不可用"; exit 2; }
echo "== 依赖容器就绪 =="

declare -A ENVV
while IFS='=' read -r k v; do
  [[ "$k" =~ ^[A-Z0-9_]+$ ]] && ENVV[$k]="$(printf '%s' "$v" | tr -d '\r')"
done < <(grep -E '^[A-Z0-9_]+=' "$ROOT/.env")

# start_backend <port> <logfile> [ENV=VAL ...]  (数组展开, 每个额外 env 必须是独立参数)
start_backend() {
  local PORT=$1 LOG=$2; shift 2
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/tutor" \
  INTERNAL_ENDPOINTS_ENABLED=true TUTOR_RETRIEVAL_FACET_RULES_ENABLED=true \
  POSTGRES_PASSWORD="${ENVV[POSTGRES_PASSWORD]}" NEO4J_PASSWORD="${ENVV[NEO4J_PASSWORD]}" \
  JWT_SECRET="${ENVV[JWT_SECRET]}" RESUME_ENC_KEY="${ENVV[RESUME_ENC_KEY]}" \
  DEEPSEEK_API_KEY="${ENVV[DEEPSEEK_API_KEY]}" DEEPSEEK_BASE_URL="${ENVV[DEEPSEEK_BASE_URL]}" \
  SILICONFLOW_API_KEY="${ENVV[SILICONFLOW_API_KEY]}" SILICONFLOW_BASE_URL="${ENVV[SILICONFLOW_BASE_URL]}" \
  LLM_MODEL_EMBED="BAAI/bge-m3" SERVER_PORT=$PORT \
  env "$@" \
  java -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 \
       -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
       -Dhttp.nonProxyHosts="localhost|127.0.0.1" \
       -jar "$ROOT/backend/target/personal-ai-tutor-0.1.0-SNAPSHOT.jar" > "$LOG" 2>&1 &
  BACKEND_PID=$!
  local code="" i
  for i in $(seq 1 45); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/readyz" || true)
    [ "$code" = 200 ] && return 0
    kill -0 "$BACKEND_PID" 2>/dev/null || return 1
    sleep 3
  done
  return 1
}

stop_backend() {
  [ -n "$BACKEND_PID" ] && { kill "$BACKEND_PID" 2>/dev/null; wait "$BACKEND_PID" 2>/dev/null; BACKEND_PID=""; }
}

# result_count <file>: JSON 中 node_id 出现次数 (grep -o 逐次计数, 避免 grep -c 行计数歧义)
result_count() { grep -o '"node_id"' "$1" 2>/dev/null | wc -l | tr -d '[:space:]'; }

retrieve() { # retrieve <port> <outfile> <query-json-escaped...> — 输出 HTTP 码
  local PORT=$1 OUT=$2; shift 2
  local body="{\"query\":\"$*\",\"topK\":5,\"mode\":\"fused\"}"
  curl -s -o "$OUT" -w '%{http_code}' --max-time 60 -X POST "http://127.0.0.1:$PORT/internal/retrieve" \
    -H 'Content-Type: application/json' -d "$body"
}

# ---------- S0 基线 ----------
echo; echo "== S0 基线 =="
if start_backend 8180 "$ROOT/.drill-s0.log" LLM_BUDGET_DAILYTOKENLIMIT=8000000 LLM_USER_DAILY_TOKEN_LIMIT=8000000; then
  H=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1:8180/healthz)
  R=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1:8180/readyz)
  Q=$(retrieve 8180 "$ROOT/.drill-out.json" "transformer attention mechanism")
  N=$(result_count "$ROOT/.drill-out.json")
  if [ "$H" = 200 ] && [ "$R" = 200 ] && [ "$Q" = 200 ] && [ "$N" -gt 0 ] 2>/dev/null; then
    report PASS "S0基线" "healthz=200 readyz=200 retrieve=200 结果数=$N"
  else
    report FAIL "S0基线" "healthz=$H readyz=$R retrieve=$Q 结果数=$N"
  fi
else
  report FAIL "S0基线" "后端启动失败"; exit 2
fi

# ---------- S5 异常输入 ----------
echo; echo "== S5 异常输入 (期望 4xx, 不允许 5xx) =="
C1=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST http://127.0.0.1:8180/internal/retrieve -H 'Content-Type: application/json' -d '{bad json')
C2=$(retrieve 8180 "$ROOT/.drill-out.json" "")
C3=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST http://127.0.0.1:8180/internal/retrieve -H 'Content-Type: application/json' -d '{"query":"test","topK":999999,"mode":"fused"}')
LONGQ=$(head -c 20000 /dev/zero | tr '\0' 'a')
C4=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 -X POST http://127.0.0.1:8180/internal/retrieve -H 'Content-Type: application/json' -d "{\"query\":\"$LONGQ\",\"topK\":5,\"mode\":\"fused\"}")
C5=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST http://127.0.0.1:8180/internal/retrieve -H 'Content-Type: application/json' -d '{"query":"test","topK":5,"mode":"unknown-mode"}')
if [ "$C1" = 400 ] || [ "$C1" = 422 ]; then report PASS "S5坏JSON" "HTTP $C1"; else report FAIL "S5坏JSON" "HTTP $C1"; fi
if [ "$C2" = 400 ] || [ "$C2" = 422 ]; then report PASS "S5空查询" "HTTP $C2"; else report FAIL "S5空查询" "HTTP $C2"; fi
if [ "${C3:-500}" -lt 500 ] 2>/dev/null; then report PASS "S5超大topK" "HTTP $C3"; else report FAIL "S5超大topK" "HTTP $C3"; fi
if [ "${C4:-500}" -lt 500 ] 2>/dev/null; then report PASS "S5超长查询20KB" "HTTP $C4"; else report FAIL "S5超长查询20KB" "HTTP $C4"; fi
if [ "${C5:-500}" -lt 500 ] 2>/dev/null; then report PASS "S5未知模式" "HTTP $C5"; else report FAIL "S5未知模式" "HTTP $C5"; fi

# ---------- S6 并发冲击 (50 并发, 自动判定) ----------
echo; echo "== S6 并发 50 =="
node --input-type=module -e '
const jobs = Array.from({length: 50}, (_, i) =>
  fetch("http://127.0.0.1:8180/internal/retrieve", {
    method: "POST", headers: {"Content-Type": "application/json"},
    body: JSON.stringify({query: "concurrency probe " + i, topK: 5, mode: "fused"}),
    signal: AbortSignal.timeout(90_000),
  }).then(async r => [r.status, (await r.json()).results?.length ?? 0])
    .catch(e => ["ERR:" + e.message, 0]));
const t0 = Date.now();
const rs = await Promise.all(jobs);
const dur = Date.now() - t0;
const ok = rs.filter(r => r[0] === 200 && r[1] > 0).length;
const s5xx = rs.filter(r => r[0] >= 500).length;
const transport = rs.filter(r => String(r[0]).startsWith("ERR")).length;
console.log(`ok=${ok}/50 s5xx=${s5xx} transport_err=${transport} total=${dur}ms`);
process.exit(ok >= 48 && s5xx === 0 ? 0 : 1);
'
if [ $? -eq 0 ]; then report PASS "S6并发50" "ok>=48 且无 5xx"; else report FAIL "S6并发50" "见上方计数"; fi

# ---------- S3 PG 宕机与恢复 ----------
echo; echo "== S3 Postgres 宕机与恢复 =="
docker stop tutor-postgres >/dev/null 2>&1
sleep 3
S3READ=$(curl -s -o "$ROOT/.drill-out.json" -w '%{http_code} %{time_total}' --max-time 8 http://127.0.0.1:8180/readyz || echo "000 timeout")
S3CODE=${S3READ%% *}; S3TIME=${S3READ##* }
if [ "$S3CODE" = 503 ] 2>/dev/null; then
  report PASS "S3就绪探针" "PG 停后 readyz=503 耗时=${S3TIME}s (Badcase 12)"
else
  report FAIL "S3就绪探针" "PG 停后 readyz=$S3CODE 耗时=${S3TIME}s (期望快速 503)"
fi
docker start tutor-postgres >/dev/null 2>&1
S3REC=""
for i in $(seq 1 15); do
  pg=$(docker inspect -f '{{.State.Health.Status}}' tutor-postgres 2>/dev/null || echo bad)
  [ "$pg" = healthy ] || { sleep 3; continue; }
  R=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1:8180/readyz)
  [ "$R" = 200 ] || { sleep 4; continue; }
  Q=$(retrieve 8180 "$ROOT/.drill-out.json" "postgres recovery probe")
  N=$(result_count "$ROOT/.drill-out.json")
  if [ "$Q" = 200 ] && [ "$N" -gt 0 ] 2>/dev/null; then S3REC=1; fi
  break
done
if [ -n "$S3REC" ]; then report PASS "S3恢复" "readyz=200 且检索可答, 无永久降级"; else report FAIL "S3恢复" "readyz/retrieve 未恢复"; fi

# ---------- S4 Neo4j 宕机与恢复 ----------
echo; echo "== S4 Neo4j 宕机与恢复 =="
docker stop tutor-neo4j >/dev/null 2>&1
sleep 5
Q4=$(retrieve 8180 "$ROOT/.drill-out.json" "neo4j down probe 检索增强生成")
N4=$(result_count "$ROOT/.drill-out.json")
if [ "$Q4" = 200 ] && [ "$N4" -gt 0 ] 2>/dev/null; then
  report PASS "S4图库降级" "Neo4j 停后 retrieve=200 结果数=$N4 (Badcase 02: 仍应可答)"
else
  report FAIL "S4图库降级" "retrieve=$Q4 结果数=$N4"
fi
docker start tutor-neo4j >/dev/null 2>&1
S4REC=""
for i in $(seq 1 15); do
  neo=$(docker inspect -f '{{.State.Health.Status}}' tutor-neo4j 2>/dev/null || echo bad)
  [ "$neo" = healthy ] || { sleep 3; continue; }
  Q=$(retrieve 8180 "$ROOT/.drill-out.json" "neo4j recovery probe")
  [ "$Q" = 200 ] && S4REC=1
  break
done
if [ -n "$S4REC" ]; then report PASS "S4恢复" "retrieve=200"; else report FAIL "S4恢复" "retrieve 未恢复"; fi
stop_backend

# ---------- S1 嵌入 provider 宕机 (独立实例, 死端口) ----------
echo; echo "== S1 嵌入 provider 宕机 (Badcase 11) =="
if start_backend 8181 "$ROOT/.drill-s1.log" LLM_BUDGET_DAILYTOKENLIMIT=8000000 LLM_USER_DAILY_TOKEN_LIMIT=8000000 SILICONFLOW_BASE_URL=http://127.0.0.1:9/v1; then
  TOTAL=0; ANSWERED=0
  for q in "什么是检索增强生成" "Transformer的面试题有哪些"; do
    Q=$(retrieve 8181 "$ROOT/.drill-out.json" "$q")
    N=$(result_count "$ROOT/.drill-out.json")
    TOTAL=$((TOTAL+1)); [ "$Q" = 200 ] && [ "$N" -gt 0 ] 2>/dev/null && ANSWERED=$((ANSWERED+1))
  done
  if [ "$ANSWERED" -gt 0 ] 2>/dev/null; then
    report PASS "S1稀疏降级" "嵌入宕机下 $ANSWERED/$TOTAL 条可答 (宽松稀疏兜底, Badcase 11)"
  else
    report FAIL "S1稀疏降级" "嵌入宕机下 $ANSWERED/$TOTAL 可答"
  fi
  stop_backend
else
  report FAIL "S1稀疏降级" "降级实例启动失败"
fi

# ---------- S2 预算耗尽 (独立实例, 限额 1K) ----------
echo; echo "== S2 预算耗尽 (Badcase 13) =="
if start_backend 8182 "$ROOT/.drill-s2.log" LLM_BUDGET_DAILYTOKENLIMIT=1000 LLM_USER_DAILY_TOKEN_LIMIT=1000; then
  S2CODE=$(curl -s -o "$ROOT/.drill-out.json" -w '%{http_code}' --max-time 30 -X POST http://127.0.0.1:8182/internal/retrieve \
    -H 'Content-Type: application/json' -d '{"query":"budget probe","topK":5,"mode":"fused"}')
  if [ "$S2CODE" = 429 ]; then
    report PASS "S2预算拒绝" "预算耗尽 → 429 带统一错误体 (Badcase 13)"
  else
    report FAIL "S2预算拒绝" "预算耗尽 → HTTP $S2CODE (期望 429)"
  fi
  stop_backend
else
  report FAIL "S2预算拒绝" "限额实例启动失败"
fi

# ---------- 摘要 ----------
echo; echo "================ 摘要 ================"
printf '%s\n' "${SUMMARY[@]}"
echo "PASS=$PASS FAIL=$FAIL"
rm -f "$ROOT"/.drill-s0.log "$ROOT"/.drill-s1.log "$ROOT"/.drill-s2.log "$ROOT"/.drill-out.json
[ "$FAIL" = 0 ] && exit 0 || exit 1
