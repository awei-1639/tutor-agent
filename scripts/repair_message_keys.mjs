// 修复 messages.content_encrypted 旧密文行: 解不开的置 NULL, 回退明文列 (双写设计, 无内容损失)。
// 用法: node scripts/repair_message_keys.mjs   (从仓库根 .env 读 POSTGRES_PASSWORD / RESUME_ENC_KEY)
// 适用场景: 无法进入 wsl/docker 的环境, 从 Windows 直连映射端口 5432。与 repair_message_keys.sh 等价。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const env = {};
for (const line of fs.readFileSync(path.join(root, '.env'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
  if (m) env[m[1]] = m[2];
}
if (!env.POSTGRES_PASSWORD || !env.RESUME_ENC_KEY) {
  console.error('FATAL: .env 缺 POSTGRES_PASSWORD 或 RESUME_ENC_KEY');
  process.exit(1);
}

const client = new pg.Client({
  host: '127.0.0.1', port: 5432, user: 'tutor', database: 'tutor',
  password: env.POSTGRES_PASSWORD,
});

// node-postgres 会误解析 $fn$ 美元引号, 函数体用单引号字符串(内部引号翻倍)
await client.connect();
await client.query(`
  CREATE FUNCTION pg_temp.try_decrypt_ok(data bytea) RETURNS boolean
  LANGUAGE plpgsql AS '
  BEGIN
    PERFORM pgp_sym_decrypt(data, current_setting(''app.enc_key''));
    RETURN true;
  EXCEPTION WHEN others THEN
    RETURN false;
  END';`);
// session 级设置: 后续每条 autocommit 语句都在同一会话, 均可读取
await client.query('SELECT set_config($1, $2, false)', ['app.enc_key', env.RESUME_ENC_KEY]);

const total = await client.query(
  'SELECT count(*)::int AS n FROM messages WHERE content_encrypted IS NOT NULL');
const bad = await client.query(
  `SELECT count(*)::int AS n FROM messages
   WHERE content_encrypted IS NOT NULL AND NOT pg_temp.try_decrypt_ok(content_encrypted)`);
console.log(`content_encrypted 非空: ${total.rows[0].n} 行, 其中解不开: ${bad.rows[0].n} 行`);

if (bad.rows[0].n > 0) {
  const u = await client.query(
    `UPDATE messages SET content_encrypted = NULL
     WHERE content_encrypted IS NOT NULL AND NOT pg_temp.try_decrypt_ok(content_encrypted)`);
  console.log(`已修复: ${u.rowCount} 行置 NULL (回退明文列)`);
}

const check = await client.query(
  'SELECT count(*)::int AS n FROM messages WHERE content_encrypted IS NOT NULL AND NOT pg_temp.try_decrypt_ok(content_encrypted)');
console.log('修复后残留坏行:', check.rows[0].n);

await client.end();
