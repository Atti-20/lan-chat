import { defineConfig } from '@playwright/test'

const port = Number(process.env.E2E_STORAGE_PORT || 19103)

// Isolated browser/IndexedDB contracts: no backend, account or LAN services.
export default defineConfig({
  testDir: './storage-specs',
  workers: 1,
  retries: 0,
  timeout: 30_000,
  outputDir: '../../output/playwright/storage-contracts',
  reporter: [['line']],
  use: { baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure' },
  webServer: {
    command: `npm --prefix ../../apps/web run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}/app/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
