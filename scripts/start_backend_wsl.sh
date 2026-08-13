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

# Keep Docker API sessions alive for the current WSL Docker lifecycle behavior.
docker events --filter container=tutor-postgres >/tmp/tutor-postgres-events.log 2>&1 &
docker events --filter container=tutor-neo4j >/tmp/tutor-neo4j-events.log 2>&1 &
docker start tutor-postgres tutor-neo4j >/dev/null 2>&1 || docker compose up -d postgres neo4j

exec java -jar "$PROJECT_ROOT/backend/target/personal-ai-tutor-0.1.0-SNAPSHOT.jar" \
  >>"$BACKEND_LOG" 2>&1
