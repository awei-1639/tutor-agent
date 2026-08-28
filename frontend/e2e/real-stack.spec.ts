import { expect, test } from '@playwright/test';

const realStack = process.env.REAL_STACK_E2E === 'true';

test.describe('real-stack smoke flow', () => {
  test.skip(!realStack, 'requires the isolated Docker full-stack environment');

  test('registers through the proxy and enforces CSRF-protected logout', async ({ context, page }) => {
    const email = `e2e-${Date.now()}@example.test`;

    await page.goto('/login');
    await page.getByRole('button', { name: '注册' }).click();
    await page.getByPlaceholder('昵称 (可选, 默认取邮箱前缀)').fill('E2E User');
    await page.getByPlaceholder('邮箱').fill(email);
    await page.getByPlaceholder('密码 (≥6 字符)').fill('correct-horse');
    await page.getByRole('button', { name: '注册并登录' }).click();

    await expect(page).toHaveURL(/\/chat$/);
    const cookies = await context.cookies();
    expect(cookies.find(cookie => cookie.name === 'tutor_access')?.httpOnly).toBe(true);
    expect(cookies.find(cookie => cookie.name === 'tutor_refresh')?.httpOnly).toBe(true);
    expect(cookies.find(cookie => cookie.name === 'tutor_csrf')?.value).toBeTruthy();
    expect(await page.evaluate(() => localStorage.getItem('tutor_token'))).toBeNull();

    const missingCsrfStatus = await page.evaluate(() =>
      fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }).then(response => response.status),
    );
    expect(missingCsrfStatus).toBe(403);

    const csrfLogoutStatus = await page.evaluate(() => {
      const csrf = document.cookie.split('; ').find(value => value.startsWith('tutor_csrf='))?.split('=').slice(1).join('');
      return fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-CSRF-Token': csrf ?? '' },
      }).then(response => response.status);
    });
    expect(csrfLogoutStatus).toBe(204);

    await expect.poll(() => context.cookies().then(cookies => cookies.find(cookie => cookie.name === 'tutor_access'))).toBeUndefined();
  });
});
