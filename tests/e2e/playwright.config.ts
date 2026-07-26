import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  // The restart/SYNC scenario deliberately restarts one application instance.
  // Keep the stack-scoped suite single-worker everywhere so that restart cannot
  // tear down connections used by another spec.
  workers: 1,
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
    video: process.env.E2E_DISABLE_VIDEO === 'true' ? 'off' : 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // CI installs Playwright Chromium. Local RC verification may opt into
        // an already-installed Chrome channel without changing test semantics.
        channel: process.env.E2E_BROWSER_CHANNEL || undefined,
      },
    },
  ],
})
