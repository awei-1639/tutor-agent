#!/usr/bin/env bash
set -euo pipefail

base_revision="${1:?Usage: check-flyway-migration-immutability.sh <base-revision>}"
migration_directory="backend/src/main/resources/db/migration"

changed_migrations="$(git diff --name-status "$base_revision" HEAD -- "$migration_directory" | awk '$1 != "A"')"
if [[ -n "$changed_migrations" ]]; then
  echo "Flyway migrations are immutable after they are introduced."
  echo "Add a new migration instead of modifying, renaming, or deleting an existing one:"
  echo "$changed_migrations"
  exit 1
fi
