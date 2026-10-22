// 验证闭环可视化依赖的两个接口：
//   GET /profile/events?traceId=...  按来源过滤画像变更
//   GET /interview/history           趋势图数据源（必须带 avgScore）
const BASE = 'http://127.0.0.1:8180';
const cookies = {};

function jar() {
  return Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
}

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  const cookie = jar();
  if (cookie) headers.Cookie = cookie;
  if (method !== 'GET' && cookies.tutor_csrf) headers['X-CSRF-Token'] = cookies.tutor_csrf;
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const line of raw) {
    const pair = line.split(';')[0];
    const at = pair.indexOf('=');
    if (at > 0) cookies[pair.slice(0, at).trim()] = pair.slice(at + 1).trim();
  }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 原样返回 */ }
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 200)}`);
  return json ?? text;
}

const problems = [];
const check = (ok, label, detail = '') => {
  console.log(`${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) problems.push(label);
};

await call('POST', '/auth/dev-login', { name: 'iv-verify' });

const all = await call('GET', '/profile/events?limit=50');
check(Array.isArray(all), '画像事件账本可读', `${all.length} 条`);

const interviewEvent = all.find(event => event.trigger === 'interview' && event.traceId);
if (!interviewEvent) {
  console.log('⚠ 该用户没有 interview 来源的画像事件，traceId 断言降级为"不存在值"检查');
} else {
  const filtered = await call(
    'GET',
    `/profile/events?limit=50&traceId=${encodeURIComponent(interviewEvent.traceId)}`,
  );
  check(
    filtered.length > 0 && filtered.every(event => event.traceId === interviewEvent.traceId),
    'traceId 过滤只返回该场事件',
    `trace=${interviewEvent.traceId.slice(0, 8)}… 命中 ${filtered.length} 条`,
  );
}

// 关键反证：参数若没生效，这里会返回全部事件而不是空数组。
const empty = await call('GET', '/profile/events?limit=50&traceId=__no_such_trace__');
check(empty.length === 0, '未知 traceId 返回空（证明过滤真的生效）', `${empty.length} 条`);

const history = await call('GET', '/interview/history?limit=10');
check(Array.isArray(history) && history.every(item => typeof item.avgScore === 'number'),
  '历史接口带 avgScore（趋势图数据源）', `${history.length} 场`);
const completed = history.filter(item => item.status === 'COMPLETED' && item.totalQuestions > 0);
console.log(`   已完成且计分的场次: ${completed.length} 场`,
  completed.map(item => `${item.avgScore.toFixed(1)}@${String(item.createdAt).slice(0, 10)}`).join(', '));
console.log(`   趋势图数据点: ${completed.length >= 2 ? '足够画折线' : '不足 2 场，界面显示引导语'}`);

if (problems.length > 0) {
  console.error(`\n失败项: ${problems.join(' / ')}`);
  process.exit(1);
}
console.log('\n🎉 接口验证全部通过');
