#!/usr/bin/env bash
# 等待评测后端就绪。
for i in $(seq 1 60); do
  code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8180/readyz 2>/dev/null || true)
  [ "$code" = "200" ] && { echo READY; exit 0; }
  sleep 2
done
echo "NOT-READY:$code"
exit 1
