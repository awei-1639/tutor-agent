#!/usr/bin/env bash
set -u

PROJECT_ROOT="/mnt/d/git/git_repo03/agent"
BACKEND_LOG="/tmp/agent-backend-dev.log"

cd "$PROJECT_ROOT"
set -a
# shellcheck disable=SC1091
. "$PROJECT_ROOT/.env"
set +a
export INTERNAL_ENDPOINTS_ENABLED=true
export JWT_SECRET="${JWT_SECRET:-agent-local-dev-secret-32-bytes-minimum-2026}"

# 针对当前 WSL Docker 生命周期行为，保持 Docker API 会话活跃。
docker events --filter container=tutor-postgres >/tmp/tutor-postgres-events.log 2>&1 &
docker events --filter container=tutor-neo4j >/tmp/tutor-neo4j-events.log 2>&1 &
docker start tutor-postgres tutor-neo4j >/dev/null 2>&1 || docker compose up -d postgres neo4j

# JVM 不读 http_proxy 环境变量；本机代理（mihomo TUN）会劫持 WSL 的 DNS/路由，
# 直连外网 LLM API 会超时——显式把 JVM 出站流量挂到同一代理上（本地/内网直连）。
# 非 login shell 不加载 .bashrc，https_proxy 可能缺失——回退到本机 mihomo mixed 端口。
PROXY_HOST="${https_proxy#http://}"; PROXY_HOST="${PROXY_HOST%%:*}"; PROXY_HOST="${PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${https_proxy##*:}"; PROXY_PORT="${PROXY_PORT:-7897}"
JVM_PROXY_ARGS="-Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT -Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttp.nonProxyHosts=localhost|127.*|192.168.*|10.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*"
echo "jvm proxy: $PROXY_HOST:$PROXY_PORT"

exec java $JVM_PROXY_ARGS -jar "$PROJECT_ROOT/backend/target/personal-ai-tutor-0.1.0-SNAPSHOT.jar" \
  >>"$BACKEND_LOG" 2>&1
