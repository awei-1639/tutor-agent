/**
 * 反问环节端到端冒烟: 开一场 15 分钟面试 (2 道主线题) → 答完 → 进入反问环节 →
 * 提问 → 看面试官回答与点评 → 输入"没有问题"结束 → 复盘报告出现 candidateAsks。
 * 用法: node .workbuddy/verify_candidate_round.mjs
 */
const BASE = 'http://127.0.0.1:8180';
const cookies = {};

function cookieHeader() {
  return Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
}

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  const cookie = cookieHeader();
  if (cookie) headers.Cookie = cookie;
  // 双提交 Cookie CSRF: 非 GET 请求必须回传 tutor_csrf。
  if (method !== 'GET' && cookies.tutor_csrf) headers['X-CSRF-Token'] = cookies.tutor_csrf;
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  for (const raw of setCookies) {
    const pair = raw.split(';')[0];
    const index = pair.indexOf('=');
    if (index > 0) cookies[pair.slice(0, index).trim()] = pair.slice(index + 1).trim();
  }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应保留原文 */ }
  return { status: res.status, json, text };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitTurn(sessionId, jobId, budgetMs = 150000) {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    const turn = await call('GET', `/interview/${sessionId}/turns/${jobId}`);
    if (turn.status !== 200) throw new Error(`轮询 turn 失败 ${turn.status}: ${turn.text}`);
    const job = turn.json;
    if (job.status === 'COMPLETED' || job.status === 'FAILED') return job;
    await sleep(1500);
  }
  throw new Error('等待评分超时');
}

async function submit(sessionId, text, label) {
  const requestId = `verify-${label}-${Date.now()}`;
  const accepted = await call('POST', `/interview/${sessionId}/answer`, { answer: text, requestId });
  if (accepted.status !== 202) throw new Error(`${label} 提交失败 ${accepted.status}: ${accepted.text}`);
  const job = await waitTurn(sessionId, accepted.json.id);
  if (job.status === 'FAILED') throw new Error(`${label} 评分失败: ${job.lastError}`);
  return job;
}

async function main() {
  const login = await call('POST', '/auth/dev-login', { name: 'iv-verify' });
  if (login.status !== 200) throw new Error(`dev-login 失败 ${login.status}: ${login.text}`);
  console.log('✅ 登录:', JSON.stringify(login.json));

  const opened = await call('POST', '/interview/open', {
    targetRole: '后端开发',
    interviewType: 'technical',
    difficulty: 'MID',
    durationMinutes: 15,
  });
  if (opened.status !== 201) throw new Error(`开面试失败 ${opened.status}: ${opened.text}`);
  const sessionId = opened.json.sessionId;
  console.log('✅ 面试已创建:', sessionId);
  console.log('   第 1 题:', opened.json.message.replace(/\n/g, ' | '));

  /**
   * 主线题走完 (15 分钟 = 2 道) 才会进入反问环节; 低分会触发两层追问链,
   * 所以这里一直答到 phase 变成 CANDIDATE_ASK 为止, 而不是固定提交 2 次。
   */
  async function phaseOf() {
    const view = await call('GET', `/interview/${sessionId}`);
    return view.json.phase;
  }

  async function lastQuestion() {
    const view = await call('GET', `/interview/${sessionId}`);
    const ai = view.json.transcript.filter((t) => t.speaker === 'ai');
    return ai.length ? ai[ai.length - 1].content : '';
  }

  let enteredCandidate = false;
  for (let round = 1; round <= 12 && !enteredCandidate; round += 1) {
    const question = await lastQuestion();
    const answer = `关于「${question.slice(0, 120)}」：工程上我会用 Spring Boot 暴露 REST 接口接收输入，`
      + '核心链路用线程池与超时隔离，失败走降级；部署用 Docker 与 K8s，暴露健康检查与 Prometheus 指标；'
      + '边界上要说明冷启动延迟、输入长度截断、依赖不可用时的降级路径，以及压测与监控验证方式。';
    const job = await submit(sessionId, answer, `main-${round}`);
    console.log(`   第 ${round} 轮 [${job.responseStatus}]:`, job.responseMessage.replace(/\n/g, ' | ').slice(0, 160));
    enteredCandidate = (await phaseOf()) === 'CANDIDATE_ASK';
  }
  console.log(enteredCandidate ? '✅ 已进入反问环节' : '❌ 未进入反问环节（已答满 12 轮）');
  if (!enteredCandidate) process.exit(1);

  const ask = await submit(sessionId, '团队现在最棘手的技术挑战是什么？', 'ask1');
  console.log('✅ 反问回答:', ask.responseMessage.replace(/\n/g, ' | '));
  const hasTip = ask.responseMessage.includes('反问点评');
  console.log(hasTip ? '✅ 含提问质量点评' : '⚠️ 未看到反问点评（可能走了兜底）');

  const end = await submit(sessionId, '没有问题了，谢谢', 'decline');
  console.log('✅ 结束轮:', end.status, '| responseStatus:', end.responseStatus);
  console.log('   ', end.responseMessage.replace(/\n/g, ' | '));

  const report = await call('GET', `/interview/${sessionId}/report`);
  const r = report.json;
  console.log('✅ 复盘: 题目数', r.totalQuestions, '| 平均分', r.avgScore?.toFixed?.(2), '| 反问条数', r.candidateAsks?.length ?? 0);
  console.log('   candidateAsks:', JSON.stringify(r.candidateAsks, null, 2));

  // 反问轮不计分: 复盘题目数应等于有分数的题数, 且反问区必须有 1 条记录。
  const asks = r.candidateAsks ?? [];
  const ok = enteredCandidate && end.responseStatus === 'COMPLETED'
    && asks.length === 1 && asks[0].reply.length > 0
    && asks[0].question.includes('技术挑战');
  console.log(ok ? '\n🎉 反问环节端到端通过' : '\n❌ 断言未全部通过');
  process.exitCode = ok ? 0 : 1;
}

main().catch((error) => {
  console.error('❌ 冒烟失败:', error.message);
  process.exitCode = 1;
});
