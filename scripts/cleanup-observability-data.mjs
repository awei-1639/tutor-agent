// 观测数据保留清理: llm_usage / tool_calls / turn_traces 超过保留期的行。
// 用法: node scripts/cleanup-observability-data.mjs [--days 90] [--apply]
//   默认 dry-run, 只打印将被删除的行数; --apply 才真正删除。
// 平台自适应: WSL/Linux 直接调 docker, Windows(cmd) 经 wsl.exe 转发 (同 run_eval.mjs)。
import { execSync } from 'node:child_process';

const args = process.argv.slice(2);
const daysIdx = args.indexOf('--days');
const days = daysIdx >= 0 ? Number(args[daysIdx + 1]) || 90 : 90;
const apply = args.includes('--apply');
const TABLES = ['llm_usage', 'tool_calls', 'turn_traces'];

function psql(sql) {
  // SQL 经 stdin 传入, 避免跨 Windows/WSL 边界的引号转义问题 (同 run_eval.mjs)。
  const cmd = 'docker exec -i tutor-postgres psql -U tutor -d tutor -tA';
  if (process.platform === 'win32') {
    return execSync('wsl.exe -e bash -c "' + cmd + '"', { shell: 'cmd.exe', encoding: 'utf8', input: sql });
  }
  return execSync(cmd, { shell: '/bin/bash', encoding: 'utf8', input: sql });
}

console.log(`观测数据保留期: ${days} 天, 模式: ${apply ? '删除' : 'dry-run (加 --apply 才删除)'}`);
let grandTotal = 0;
for (const table of TABLES) {
  const count = Number(psql(
    `SELECT count(*) FROM ${table} WHERE created_at < now() - interval '${days} days';`).trim()) || 0;
  grandTotal += count;
  console.log(`${table.padEnd(12)} 将删除 ${String(count).padStart(6)} 行`);
  if (apply && count > 0) {
    psql(`DELETE FROM ${table} WHERE created_at < now() - interval '${days} days';`);
    console.log(`${table.padEnd(12)} 已删除`);
  }
}
console.log(apply ? `合计删除 ${grandTotal} 行。` : `合计可释放 ${grandTotal} 行 (未删除)。`);
console.log('提示: eval_runs 与 evals/results/ 是基线溯源证据, 本脚本不清理, 请按 docs/local-data-inventory.md 手工归档。');
