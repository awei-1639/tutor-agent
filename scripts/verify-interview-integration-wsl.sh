#!/usr/bin/env bash
# 请在 WSL 中运行，Testcontainers 可在其中访问 Docker 守护进程。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/backend"

# Testcontainers 已为该测试提供进程隔离的 PostgreSQL。在 Maven 的 WSL JVM 中运行
# 可避免 Surefire 的 fork-exit 看门狗，以及 docker-java 仍在关闭 HTTP 连接时产生的
# 虚假 30 秒警告。
mvn -B -DforkCount=0 -DrunIntegrationTests=true -Dtest=InterviewSessionPostgresIT test
