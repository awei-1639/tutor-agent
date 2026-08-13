#!/usr/bin/env bash
# Run from WSL, where Testcontainers can access the Docker daemon.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/backend"

# Testcontainers already gives this test a process-isolated PostgreSQL. Running
# it in Maven's WSL JVM avoids Surefire's fork-exit watchdog and its spurious
# 30-second warning when docker-java is still closing an HTTP connection.
mvn -B -DforkCount=0 -DrunIntegrationTests=true -Dtest=InterviewSessionPostgresIT test
