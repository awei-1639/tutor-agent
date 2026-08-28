import { defineConfig, devices } from '@playwright/test';

const realStack = process.env.REAL_STACK_E2E === 'true';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // CI 机器较慢，偶发时序竞态会让 mock 拦截与页面渲染错位。重试仅兜底偶发失败；
  // 真实缺陷在重试后仍会稳定失败，不会被掩盖。配合 trace: 'on-first-retry' 保留首次现场。
  retries: process.env.CI ? 2 : 0,
  reporter: 'line',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? (realStack ? 'http://127.0.0.1:8081' : 'http://127.0.0.1:4173'),
    trace: 'on-first-retry',
    ...devices['Desktop Chrome'],
  },
  webServer: realStack ? undefined : {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: true,
  },
});
