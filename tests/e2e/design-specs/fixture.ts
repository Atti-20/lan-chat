import { expect, type Page } from '@playwright/test'

export async function openFixture(page: Page, options: {
  theme?: 'light' | 'dark'; width?: number; runtime?: 'web' | 'tauri'; fontScale?: number; stored?: string | null;
} = {}) {
  const { theme = 'light', width = 1440, runtime = 'web', fontScale = 1, stored = theme } = options
  await page.setViewportSize({ width, height: 1000 })
  await page.emulateMedia({ colorScheme: theme })
  await page.clock.setFixedTime(new Date('2026-09-09T00:00:00+08:00'))
  await page.addInitScript(({ runtime, fontScale, stored }) => {
    if (!sessionStorage.getItem('meshx_design_initialized')) {
      if (stored === null) localStorage.removeItem('lanchat_theme')
      else localStorage.setItem('lanchat_theme', stored)
      sessionStorage.setItem('meshx_design_initialized', '1')
    }
    document.addEventListener('DOMContentLoaded', () => {
      // Only the real Tauri CSS context is tested; this does not fake native IPC.
      document.documentElement.dataset.runtime = runtime
      document.documentElement.style.fontSize = `${16 * fontScale}px`
    }, { once: true })
  }, { runtime, fontScale, stored })
  await page.route('**/app/__design_foundation__', route => route.fulfill({ contentType: 'text/html', body:
    '<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="theme-color" content="#edf0f4"><link rel="icon" href="data:,"></head><body><div id="app"></div><script type="module" src="/app/tests/design-fixtures/main.ts"></script></body></html>' }))
  await page.goto('/app/__design_foundation__')
  await expect(page.getByTestId('design-fixture')).toBeVisible()
  await expect(page.locator('.message-row')).toHaveCount(7)
  await page.evaluate(() => document.fonts.ready)
}
