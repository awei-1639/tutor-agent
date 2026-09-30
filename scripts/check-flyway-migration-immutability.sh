#!/usr/bin/env bash
set -euo pipefail

base_revision="${1:?Usage: check-flyway-migration-immutability.sh <base-revision>}"
migration_directory="backend/src/main/resources/db/migration"

# 正常 push 的 base 是 master 的上一头，checkout（fetch-depth: 0）必然包含；
# 但强推/历史重写会让 base 成为孤儿对象，git diff 直接报 bad object 退出 128。
# pull_request 事件还会携带陈旧的 base.sha（Dependabot rebase 后的已知怪癖）。
# 两种情况下都退回 merge-base(origin/master, HEAD) 做增量校验——不能退回全历史扫描：
# 立规矩之前 V6 曾被合法修改过，全历史扫描必然误报，会卡死所有 PR。
if git cat-file -e "${base_revision}^{commit}" 2>/dev/null; then
  changed_migrations="$(git diff --name-status "$base_revision" HEAD -- "$migration_directory" | awk '$1 != "A"')"
elif base="$(git merge-base origin/master HEAD 2>/dev/null)"; then
  echo "::warning::Base revision ${base_revision} is not present in this checkout; using merge-base ${base} instead."
  changed_migrations="$(git diff --name-status "$base" HEAD -- "$migration_directory" | awk '$1 != "A"')"
else
  echo "::warning::Base revision ${base_revision} is not present and no merge base found; skipping migration diff."
  changed_migrations=""
fi

if [[ -n "$changed_migrations" ]]; then
  echo "Flyway migrations are immutable after they are introduced."
  echo "Add a new migration instead of modifying, renaming, or deleting an existing one:"
  echo "$changed_migrations"
  exit 1
fi
