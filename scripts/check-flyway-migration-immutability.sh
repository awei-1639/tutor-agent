#!/usr/bin/env bash
set -euo pipefail

base_revision="${1:?Usage: check-flyway-migration-immutability.sh <base-revision>}"
migration_directory="backend/src/main/resources/db/migration"

# 正常 push 的 base 是 master 的上一头，checkout（fetch-depth: 0）必然包含；
# 但强推/历史重写会让 base 成为孤儿对象，git diff 直接报 bad object 退出 128。
# 此时退化为全历史校验：任何被修改/重命名/删除过的迁移文件都算违规。
if git cat-file -e "${base_revision}^{commit}" 2>/dev/null; then
  changed_migrations="$(git diff --name-status "$base_revision" HEAD -- "$migration_directory" | awk '$1 != "A"')"
else
  echo "::warning::Base revision ${base_revision} is not present in this checkout; verifying full history instead."
  changed_migrations="$(git log --diff-filter=MRD --name-only --format= -- "$migration_directory")"
fi

if [[ -n "$changed_migrations" ]]; then
  echo "Flyway migrations are immutable after they are introduced."
  echo "Add a new migration instead of modifying, renaming, or deleting an existing one:"
  echo "$changed_migrations"
  exit 1
fi
