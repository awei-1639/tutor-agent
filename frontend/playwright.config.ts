import { defineConfig, devices } from '@playwright/test';

const realStack = process.env.REAL_STACK_E2E === 'true';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
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
