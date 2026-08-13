import { test, expect } from '@playwright/test';

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'tutor_access', value: 'test-access', domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' },
    { name: 'tutor_csrf', value: 'test-csrf', domain: '127.0.0.1', path: '/', httpOnly: false, sameSite: 'Lax' },
  ]);
  await page.route('**/api/auth/login', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ user_id: 7, token: 'must-not-be-stored', name: 'Test User' }),
  }));
  await page.route('**/api/conversations', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/chat', async route => {
    expect(route.request().headers()['x-csrf-token']).toBe('test-csrf');
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: [
        'data: {"conversation_id":1,"trace_id":"e2e"}\n\n',
        'data: {"text":"<img src=x onerror=alert(1)>安全内容"}\n\n',
        'data: {"message_id":1}\n\n',
      ].join(''),
    });
  });
});

test('keeps access token out of localStorage and sanitizes model HTML', async ({ page }) => {
  await page.goto('/login');
  await page.locator('input[type="email"]').fill('test@example.com');
  await page.locator('input[type="password"]').fill('correct-horse');
  await page.locator('form').getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL(/\/chat$/);
  expect(await page.evaluate(() => localStorage.getItem('tutor_token'))).toBeNull();
  await page.getByPlaceholder('输入你的问题，Enter 发送 / Shift+Enter 换行').fill('请测试安全输出');
  await page.getByRole('button', { name: '发送' }).click();

  await expect(page.getByText('安全内容')).toBeVisible();
  await expect(page.locator('img')).toHaveCount(0);
});
