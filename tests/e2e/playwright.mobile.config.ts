import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './mobile-specs', workers: 1, retries: 0, timeout: 30 * 60_000,
  outputDir: '../../output/playwright/mx-a05/runs', reporter: [['line']],
  use: { baseURL: 'http://127.0.0.1:5194', viewport: { width: 1280, height: 900 },
    timezoneId: 'Asia/Shanghai', locale: 'zh-CN', actionTimeout: 15_000 },
})
