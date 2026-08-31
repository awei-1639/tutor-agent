#!/usr/bin/env bash
# 记忆评测一键脚本 (WSL 内运行): 在单个 wsl 会话内完成 起库→起后端→等就绪→跑评测→关后端。
# 必须单会话内完成: wsl.exe 客户端断开后 WSL VM 会关停, 杀掉后台后端与容器。
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# 独立端口便于与开发实例并存；被占用时必须直接失败, 不能沿用别人的进程 (见下方就绪判断)。
PORT="${MEMORY_EVAL_PORT:-8180}"
BASE_URL="http://localhost:${PORT}"
export MEMORY_EVAL_BASE_URL="$BASE_URL"

if timeout 2 bash -c "</dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then
  echo "端口 $PORT 已被占用。就绪判断只看端口连通性, 继续跑会打到别人的进程 (可能是旧代码),"
  echo "评测结果将不可信。请先停掉占用者, 或用 MEMORY_EVAL_PORT=<空闲端口> 重跑。"
  ss -ltnp 2>/dev/null | grep ":$PORT" || true
  exit 1
fi

docker compose --env-file .env up -d postgres neo4j >/dev/null 2>&1
sleep 4

cd backend
set -a
source ../.env
set +a
export INTERNAL_ENDPOINTS_ENABLED="${INTERNAL_ENDPOINTS_ENABLED:-true}"
export SERVER_PORT="$PORT"

# fork=false: spring-boot:run 默认另起 JVM, 那样 kill $APP_PID 只杀掉 maven,
# 被 fork 的后端会活下来继续占端口 (本脚本此前每跑一次泄漏一个后端)。
nohup mvn -q spring-boot:run -Dspring-boot.run.fork=false > tutor-boot.log 2>&1 &
APP_PID=$!
echo "backend starting (pid $APP_PID, port $PORT)..."

ready=0
# WSL 从 /mnt/d 读类路径极慢: Spring context 初始化本身就要 60s+, 整体启动约 4-5 分钟。
# 上限给到 10 分钟, 否则会在后端仍在启动时误判超时。
READY_TIMEOUT_SECONDS="${MEMORY_EVAL_READY_TIMEOUT:-600}"
for _ in $(seq 1 $((READY_TIMEOUT_SECONDS / 5))); do
  sleep 5
  if timeout 2 bash -c "</dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then
    ready=1
    break
  fi
  # 后端进程若已退出, 不再空等
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "backend process died; last log lines:"
    tail -15 tutor-boot.log
    exit 1
  fi
done
if [ "$ready" != "1" ]; then
  echo "backend not ready in time; last log lines:"
  tail -15 tutor-boot.log
  kill "$APP_PID" 2>/dev/null
  exit 1
fi
echo "backend ready; running memory eval smoke..."

# /internal 新端点若被部署环境的鉴权策略拦截, 用注册用户的 access cookie 走通用分支。
JAR=$(mktemp)
EMAIL="memory-eval-$(date +%s)@eval.local"
COOKIE=$(curl -s -c "$JAR" -X POST "$BASE_URL/auth/register"   -H 'Content-Type: application/json'   -d "{\"email\":\"$EMAIL\",\"password\":\"eval-pass-123\",\"name\":\"memory-eval\"}" >/dev/null   && awk '/tutor_access/ {print $6"="$7}' "$JAR" | head -1)
rm -f "$JAR"
[ -n "$COOKIE" ] && export MEMORY_EVAL_COOKIE="$COOKIE" && echo "eval cookie acquired (${COOKIE%%=*})"
[ -z "$COOKIE" ] && echo "WARN: no access cookie; falling back to /internal exemption"

if [ "${MEMORY_EVAL_PROBE:-0}" = "1" ]; then
  echo "=== probe retrieve:"
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/internal/retrieve" \
    -H 'Content-Type: application/json' -d '{"query":"test","topK":1}'
  echo "=== probe memory-seed (headers+body):"
  curl -si -X POST "$BASE_URL/internal/memory-seed" \
    -H 'Content-Type: application/json' -d '{"userId":990001,"episodes":[],"facts":[]}' | head -12
  echo "=== probe memory-recall:"
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/internal/memory-recall" \
    -H 'Content-Type: application/json' -d '{"userId":990001,"query":"test","topK":5}'
fi

node "$repo_root/evals/run_memory_eval.mjs" --smoke
EVAL_RC=$?

kill "$APP_PID" 2>/dev/null
echo "backend stopped; eval exit=$EVAL_RC"
exit "$EVAL_RC"
