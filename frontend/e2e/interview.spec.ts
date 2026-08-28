import { test, expect, type Page } from '@playwright/test';

// 本文件三个用例共用同一套 mock 路由和固定的 session-1，并发执行会相互干扰
// （登录跳转与 page.route 拦截存在竞态，CI 上表现为随机超时）。改为串行。
test.describe.configure({ mode: 'serial' });

function interviewSession(status = 'IN_PROGRESS') {
  return {
    sessionId: 'session-1', status, targetRole: 'Agent 工程师', topic: 'Agent 工具调用',
    mainQuestionsAsked: status === 'IN_PROGRESS' ? 1 : 2,
    deadlineAt: new Date(Date.now() + 20 * 60_000).toISOString(),
    transcript: [{ speaker: 'ai', content: '问题 1：如何保证 Agent 工具调用安全？' }],
  };
}

test.beforeEach(async ({ context, page }) => {
  // InterviewPage 从 sessionStorage 恢复进行中的面试，且只在没有 session 时渲染目标岗位表单。
  // 串行执行会复用同一个 worker，上一个用例残留的 session 会让下一个用例等不到表单。
  await page.addInitScript(() => {
    sessionStorage.removeItem('tutor_active_interview_session');
    sessionStorage.removeItem('tutor_active_interview_turn');
    sessionStorage.removeItem('tutor_active_interview_retry');
  });
  await context.addCookies([
    { name: 'tutor_access', value: 'test-access', domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' },
    { name: 'tutor_csrf', value: 'test-csrf', domain: '127.0.0.1', path: '/', httpOnly: false, sameSite: 'Lax' },
  ]);
  await page.route('**/api/auth/login', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ user_id: 7, name: 'Test User' }),
  }));
  await page.route('**/api/conversations', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/interview/history*', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
});

async function loginAndOpen(page: Page) {
  await page.goto('/login');
  await page.locator('input[type="email"]').fill('test@example.com');
  await page.locator('input[type="password"]').fill('correct-horse');
  await page.locator('form').getByRole('button', { name: '登录' }).click();
  await page.goto('/interview');
}

test('completes the interview and exposes asynchronous learning status', async ({ page }) => {
  let completionCalls = 0;
  await page.route('**/api/interview/open', route => route.fulfill({
    status: 201, contentType: 'application/json',
    body: JSON.stringify({ sessionId: 'session-1', status: 'IN_PROGRESS', message: '问题 1：如何保证 Agent 工具调用安全？' }),
  }));
  await page.route('**/api/interview/session-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(interviewSession()) }));
  await page.route('**/api/interview/session-1/answer', route => route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(turnJob('PROCESSING')) }));
  await page.route('**/api/interview/session-1/turns/turn-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(turnJob('COMPLETED', 'COMPLETED', '评分: 8/10\n\n面试结束，复盘报告已准备好。')) }));
  await page.route('**/api/interview/session-1/report', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ totalQuestions: 1, avgScore: 8, scoreConfidence: 0.85, strengths: ['说明了权限校验'], improvements: ['补充超时策略'], resources: ['skill:Agent'] }),
  }));
  await page.route('**/api/interview/session-1/completion', route => {
    completionCalls += 1;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      sessionId: 'session-1', status: completionCalls === 1 ? 'queued' : 'completed', attempts: completionCalls,
      lastError: null, createdAt: new Date().toISOString(), startedAt: null, finishedAt: completionCalls === 1 ? null : new Date().toISOString(),
    }) });
  });

  await loginAndOpen(page);
  await page.getByPlaceholder('例如: NLP 算法工程师 / 后端开发').fill('Agent 工程师');
  await page.getByRole('button', { name: '开始面试' }).click();
  await expect(page.getByText('问题 1：如何保证 Agent 工具调用安全？')).toBeVisible();
  await page.getByPlaceholder('输入你的回答').fill('先校验 schema 和权限，再设置超时。');
  await page.getByRole('button', { name: '发送' }).click();

  await expect(page.getByRole('heading', { name: '复盘报告' })).toBeVisible();
  await expect(page.getByText('学习闭环：等待处理')).toBeVisible();
  await expect(page.getByText('学习闭环：已完成')).toBeVisible({ timeout: 5000 });
  expect(completionCalls).toBeGreaterThanOrEqual(2);
});

test('preserves an answer and retries after a transient submission failure', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/interview/open', route => route.fulfill({
    status: 201, contentType: 'application/json',
    body: JSON.stringify({ sessionId: 'session-1', status: 'IN_PROGRESS', message: '问题 1：如何保证 Agent 工具调用安全？' }),
  }));
  await page.route('**/api/interview/session-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(interviewSession()) }));
  await page.route('**/api/interview/session-1/answer', route => {
    attempts += 1;
    if (attempts === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '评分服务暂时不可用' }) });
    return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(turnJob('PROCESSING')) });
  });
  await page.route('**/api/interview/session-1/turns/turn-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(turnJob('COMPLETED', 'IN_PROGRESS', '评分: 6/10\n\n追问：请说明如何处理工具超时。')) }));

  await loginAndOpen(page);
  await page.getByRole('button', { name: '开始面试' }).click();
  const answer = '先校验参数，再调用工具。';
  await page.getByPlaceholder('输入你的回答').fill(answer);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用');
  await expect(page.getByPlaceholder('输入你的回答')).toHaveValue(answer);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.getByText('追问：请说明如何处理工具超时。')).toBeVisible();
  expect(attempts).toBe(2);
});

test('keeps a completed interview when the first report load fails', async ({ page }) => {
  let reportAttempts = 0;
  await page.route('**/api/interview/open', route => route.fulfill({
    status: 201, contentType: 'application/json',
    body: JSON.stringify({ sessionId: 'session-1', status: 'IN_PROGRESS', message: '问题 1：如何保证 Agent 工具调用安全？' }),
  }));
  await page.route('**/api/interview/session-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(interviewSession()) }));
  await page.route('**/api/interview/session-1/answer', route => route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(turnJob('PROCESSING')) }));
  await page.route('**/api/interview/session-1/turns/turn-1', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(turnJob('COMPLETED', 'COMPLETED', '评分: 8/10\n\n面试结束，复盘报告已准备好。')) }));
  await page.route('**/api/interview/session-1/report', route => {
    reportAttempts += 1;
    if (reportAttempts === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '报告服务暂时不可用' }) });
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ totalQuestions: 1, avgScore: 8, scoreConfidence: 0.85, strengths: ['说明了权限校验'], improvements: ['补充超时策略'], resources: ['skill:Agent'] }),
    });
  });

  await loginAndOpen(page);
  await page.getByRole('button', { name: '开始面试' }).click();
  const answer = '先校验参数，再调用工具。';
  await page.getByPlaceholder('输入你的回答').fill(answer);
  await page.getByRole('button', { name: '发送' }).click();

  await expect(page.getByRole('heading', { name: '复盘报告' })).toBeVisible();
  await expect(page.getByText(answer)).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用');
  await page.getByRole('button', { name: '重新加载报告' }).click();
  await expect(page.getByText('评分置信度')).toBeVisible();
  expect(reportAttempts).toBe(2);
});

function turnJob(status: string, responseStatus?: string, responseMessage?: string) {
  return { id: 'turn-1', sessionId: 'session-1', requestId: 'request-1', status, attempts: 1,
    responseStatus: responseStatus ?? null, responseMessage: responseMessage ?? null, lastError: null,
    createdAt: new Date().toISOString(), finishedAt: status === 'COMPLETED' ? new Date().toISOString() : null };
}
