// LLM 成本报表: 汇总 llm_usage 里的调用次数与 token 消耗, 按用途/模型分组。
// 用法: node scripts/llm-cost-report.mjs [--days 7]
// 平台自适应: WSL/Linux 直接调 docker, Windows(cmd) 经 wsl.exe 转发 (同 run_eval.mjs)。
import { execSync } from 'node:child_process';

const args = process.argv.slice(2);
const daysIdx = args.indexOf('--days');
const days = daysIdx >= 0 ? Number(args[daysIdx + 1]) || 7 : 7;

function psql(sql) {
  // SQL 经 stdin 传入, 避免跨 Windows/WSL 边界的引号转义问题 (同 run_eval.mjs)。
  const cmd = 'docker exec -i tutor-postgres psql -U tutor -d tutor -tA';
  if (process.platform === 'win32') {
    return execSync('wsl.exe -e bash -c "' + cmd + '"', { shell: 'cmd.exe', encoding: 'utf8', input: sql });
  }
  return execSync(cmd, { shell: '/bin/bash', encoding: 'utf8', input: sql });
}

const rows = psql(`
  SELECT purpose, model, count(*), COALESCE(sum(tokens_in),0), COALESCE(sum(tokens_out),0)
  FROM llm_usage
  WHERE created_at > now() - interval '${days} days' AND status='ok'
  GROUP BY 1,2 ORDER BY 3+4+5 DESC;`).trim();

if (!rows) {
  console.log(`最近 ${days} 天没有 llm_usage 记录。`);
  process.exit(0);
}

console.log(`LLM 消耗报表 (最近 ${days} 天, status=ok):`);
console.log('purpose   model                calls      tokens_in     tokens_out         total');
let grandTotal = 0;
for (const line of rows.split('\n')) {
  const [purpose, model, calls, tin, tout] = line.split('|');
  const total = Number(tin) + Number(tout);
  grandTotal += total;
  console.log(
    purpose.padEnd(9) + ' ' + model.padEnd(20) + ' ' +
    String(calls).padStart(6) + ' ' + Number(tin).toLocaleString('en-US').padStart(13) + ' ' +
    Number(tout).toLocaleString('en-US').padStart(13) + ' ' + total.toLocaleString('en-US').padStart(13));
}
console.log('-'.repeat(78));
console.log('合计 token:'.padEnd(38) + grandTotal.toLocaleString('en-US').padStart(40));
console.log('\n价格请按供应商现价自行折算; purpose→模型映射见 backend/src/main/resources/application.yml llm.routing。');
