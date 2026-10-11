#!/usr/bin/env bash
# seed 重导入一键脚本：在单个 WSL 会话内完成，避免 VM 空闲关机杀容器。
# 用法（PowerShell）: wsl bash /mnt/d/git/git_repo03/agent/scripts/reimport_seed.sh
set -euo pipefail

SEED_DIR="/mnt/c/Users/lenovo/AppData/Local/Temp/tutor-seed"
ENV_FILE="/mnt/d/git/git_repo03/agent/.env"
PW=$(grep -E '^NEO4J_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')

if [ -z "$PW" ]; then PW="tutor_dev_only"; fi
for f in seed.cypher kg_entity_aliases.sql kg_chunks.sql jobs.sql; do
  [ -f "$SEED_DIR/$f" ] || { echo "缺少 $SEED_DIR/$f"; exit 1; }
done

echo "== 1/5 启动容器 =="
docker start tutor-postgres tutor-neo4j >/dev/null

echo "== 2/5 等待 Neo4j 就绪（最多 90s）=="
ready=0
for i in $(seq 1 30); do
  if docker exec tutor-neo4j cypher-shell -u neo4j -p "$PW" "RETURN 1;" >/dev/null 2>&1; then
    ready=1; echo "Neo4j 已就绪（等了 $((i*3))s）"; break
  fi
  sleep 3
done
[ "$ready" = 1 ] || { echo "Neo4j 90s 内未就绪，退出"; exit 1; }

echo "== 3/5 导入 Neo4j 图谱 =="
docker exec -i tutor-neo4j cypher-shell -u neo4j -p "$PW" < "$SEED_DIR/seed.cypher"

echo "== 4/5 导入 Postgres 三件 =="
docker exec -i tutor-postgres psql -U tutor -d tutor -q -v ON_ERROR_STOP=1 < "$SEED_DIR/kg_entity_aliases.sql"
docker exec -i tutor-postgres psql -U tutor -d tutor -q -v ON_ERROR_STOP=1 < "$SEED_DIR/kg_chunks.sql"
docker exec -i tutor-postgres psql -U tutor -d tutor -q -v ON_ERROR_STOP=1 < "$SEED_DIR/jobs.sql"

echo "== 5/5 验证：>150 字的 chunk 数量 =="
docker exec -i tutor-postgres psql -U tutor -d tutor -c "select count(*) from kg_chunks where length(chunk_text) > 150;"

echo "== DONE 导入完成 =="
