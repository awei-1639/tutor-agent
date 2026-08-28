#!/usr/bin/env bash
# 真实本地容器演练：依赖健康 → 后端探针 → 前端静态资源与 /api 反代。
set -euo pipefail

compose=(docker compose -f docker-compose.yml -f docker-compose.local.yml)
export JWT_SECRET="${JWT_SECRET:-local_container_validation_secret_at_least_32_bytes}"
backend_port="${TUTOR_BACKEND_PORT:-8180}"
frontend_port="${TUTOR_FRONTEND_PORT:-8081}"

wait_http() {
  local url="$1"
  local label="$2"
  for _ in $(seq 1 40); do
    # WSL/企业网络可能设置 HTTP(S)_PROXY；本机回环探针必须绕过代理。
    if curl --noproxy '*' --fail --silent "$url" >/dev/null 2>&1; then
      echo "$label: ok"
      return 0
    fi
    sleep 3
  done
  echo "$label: timed out" >&2
  "${compose[@]}" logs --tail=120 backend frontend >&2 || true
  return 1
}

# 烟雾测试必须覆盖当前工作区；复用带标签的本地镜像可能会错误报告先前构建的
# 代码和迁移已通过。
"${compose[@]}" up -d --build
wait_http "http://127.0.0.1:${backend_port}/healthz" "backend healthz"
wait_http "http://127.0.0.1:${backend_port}/readyz" "backend readyz"
wait_http "http://127.0.0.1:${frontend_port}/" "frontend static"
wait_http "http://127.0.0.1:${frontend_port}/api/healthz" "frontend api proxy"

"${compose[@]}" ps
