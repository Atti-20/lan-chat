import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  workers: process.env.CI ? 1 : undefined,
  retries: process.env.CI ? 1 : 0,
  timeout: 45_000,
  expect: {
    timeout: 15_000,
  },
  outputDir: '../../output/playwright/test-results',
  reporter: [
    ['line'],
    ['html', {
      outputFolder: '../../output/playwright/report',
      open: 'never',
    }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
      },
    },
  ],
})
