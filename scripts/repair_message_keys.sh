#!/usr/bin/env bash
# 修复 messages.content_encrypted 中无法用当前 RESUME_ENC_KEY 解密的行（历史密钥不一致遗留）。
# 原理：content 列始终存有脱敏明文（appendMessage 同时写 content + content_encrypted），
#      将解密失败的 content_encrypted 置 NULL，读取路径 COALESCE 自动回退到 content，无可读内容损失。
# 用法（PowerShell）: wsl bash /mnt/d/git/git_repo03/agent/scripts/repair_message_keys.sh
set -euo pipefail

ENV_FILE="/mnt/d/git/git_repo03/agent/.env"
KEY=$(grep -E '^RESUME_ENC_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')

if [ -z "$KEY" ]; then
  echo "RESUME_ENC_KEY 为空：历史密文行本就无人能解，直接全部置 NULL 处理"
  docker exec -i tutor-postgres psql -U tutor -d tutor -q -v ON_ERROR_STOP=1 \
    -c "UPDATE messages SET content_encrypted = NULL WHERE content_encrypted IS NOT NULL;"
  echo "DONE"
  exit 0
fi

docker exec -i tutor-postgres psql -U tutor -d tutor -v ON_ERROR_STOP=1 -v key="$KEY" <<'SQL'
-- psql 变量替换在 $$ 块内不生效, 因此用临时函数封装 try-decrypt, 在普通语句里传 :'key'
CREATE OR REPLACE FUNCTION pg_temp.try_decrypt(data bytea, key text) RETURNS text AS $fn$
BEGIN
  RETURN pgp_sym_decrypt(data, key);
EXCEPTION WHEN others THEN RETURN NULL;
END
$fn$ LANGUAGE plpgsql;

UPDATE messages SET content_encrypted = NULL
WHERE content_encrypted IS NOT NULL
  AND pg_temp.try_decrypt(content_encrypted, :'key') IS NULL;
SQL

echo "DONE 修复完成，/conversations 应恢复"
