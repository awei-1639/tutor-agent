#!/usr/bin/env bash
set -u

PROJECT_ROOT="/mnt/d/git/git_repo03/agent"
BACKEND_LOG="/tmp/agent-backend-live.log"
POSTGRES_EVENTS="/tmp/tutor-postgres-events.log"
NEO4J_EVENTS="/tmp/tutor-neo4j-events.log"
BACKEND_PID=""
EVENT_PIDS=()
EVAL_LIMIT="${EVAL_LIMIT:-10}"

cleanup() {
  if [ -n "$BACKEND_PID" ]; then kill "$BACKEND_PID" 2>/dev/null || true; fi
  for pid in "${EVENT_PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
rm -f "$BACKEND_LOG" "$POSTGRES_EVENTS" "$NEO4J_EVENTS"

# 保持 Docker API 会话，避免当前 WSL Docker 的空闲生命周期机制提前回收容器。
docker events --since 0s --filter container=tutor-postgres --format '{{.Action}}' >"$POSTGRES_EVENTS" 2>&1 &
EVENT_PIDS+=("$!")
docker events --since 0s --filter container=tutor-neo4j --format '{{.Action}}' >"$NEO4J_EVENTS" 2>&1 &
EVENT_PIDS+=("$!")
docker start tutor-postgres tutor-neo4j >/dev/null 2>&1 || true
sleep 8

set -a
# shellcheck disable=SC1091
. "$PROJECT_ROOT/.env"
set +a
export INTERNAL_ENDPOINTS_ENABLED=true
export JWT_SECRET="agent-live-eval-test-secret-32-bytes-minimum-2026"

java -jar "$PROJECT_ROOT/backend/target/personal-ai-tutor-0.1.0-SNAPSHOT.jar" >"$BACKEND_LOG" 2>&1 &
BACKEND_PID="$!"

ready=0
for _ in $(seq 1 45); do
  health_status=$(curl --noproxy '*' -sS -o /tmp/agent-health-response.json -w '%{http_code}' \
    http://127.0.0.1:8180/actuator/health 2>/dev/null || true)
  if printf '%s' "$health_status" | grep -Eq '^[2-5][0-9][0-9]$'; then
    ready=1
    break
  fi
  sleep 2
done

if [ "$ready" -ne 1 ]; then
  echo "BACKEND_NOT_READY"
  tail -n 120 "$BACKEND_LOG"
  exit 1
fi

echo "HEALTH"
curl --noproxy '*' -sS -i http://127.0.0.1:8180/actuator/health
echo

echo "START"
start_response=$(curl --noproxy '*' -sS -i -X POST http://127.0.0.1:8180/internal/evals \
  -H 'Content-Type: application/json' \
  --data "{\"topK\":3,\"limit\":${EVAL_LIMIT},\"modes\":[\"fused\"]}")
echo "$start_response"
run_id=$(printf '%s' "$start_response" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
if [ -z "$run_id" ]; then
  echo "EVAL_START_FAILED"
  tail -n 120 "$BACKEND_LOG"
  exit 1
fi

for _ in $(seq 1 300); do
  detail=$(curl --noproxy '*' -sS "http://127.0.0.1:8180/internal/evals/$run_id")
  status=$(printf '%s' "$detail" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
  echo "POLL status=$status"
  if [ "$status" = "completed" ] || [ "$status" = "failed" ]; then
    echo "$detail"
    exit 0
  fi
  sleep 2
done

echo "EVAL_TIMEOUT"
tail -n 120 "$BACKEND_LOG"
exit 1
