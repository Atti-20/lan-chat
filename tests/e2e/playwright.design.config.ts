import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const port = Number(process.env.E2E_DESIGN_PORT || 19104)
const baseline = process.env.MESHX_DESIGN_BASELINE_DIR || fileURLToPath(new URL('../../output/playwright/mx-a04/baseline-v2', import.meta.url))

export default defineConfig({
  testDir: './design-specs', workers: 1, retries: 0, timeout: 30_000,
  outputDir: '../../output/playwright/mx-a04/runs', reporter: [['line']],
  snapshotPathTemplate: `${baseline}/{arg}{ext}`,
  use: { baseURL: `http://127.0.0.1:${port}`, timezoneId: 'Asia/Shanghai', locale: 'zh-CN',
    trace: 'retain-on-failure' },
  projects: [
    { name: 'dom', testMatch: 'design.spec.ts' },
    { name: 'visual', testMatch: 'visual.spec.ts' },
  ],
  webServer: {
    command: `npm --prefix ../../apps/web run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}/app/`, reuseExistingServer: false, timeout: 30_000,
  },
})
