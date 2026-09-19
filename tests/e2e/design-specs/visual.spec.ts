import { expect, test } from '@playwright/test'
import { access, mkdir, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { openFixture } from './fixture.js'

for (const theme of ['light', 'dark'] as const) {
  for (const runtime of ['web', 'tauri'] as const) {
    for (const width of [320, 1440]) {
      for (const fontScale of [1, 2]) {
      test(`${theme} ${runtime} ${width} font ${fontScale} preserves the existing component baseline`, async ({ page }, info) => {
        await openFixture(page, { theme, runtime, width, fontScale })
        await page.getByRole('region', { name: '基础控件' }).getByRole('button', { name: '发送消息', exact: true }).hover()
        await page.getByRole('button', { name: '添加', exact: true }).focus()
        const file = `${theme}-${runtime}-${width}-${fontScale}x.png`
        const expected = info.snapshotPath(file)
        const screenshot = await page.screenshot({ fullPage: true, animations: 'disabled', caret: 'hide' })
        if (process.env.MESHX_DESIGN_CAPTURE_BASELINE === '1') {
          // First capture only, before product edits. Never overwrite reviewed baselines.
          await mkdir(dirname(expected), { recursive: true })
          await writeFile(expected, screenshot, { flag: 'wx' })
        } else {
          await access(expected) // Missing evidence must fail; no silent snapshot creation.
          expect(screenshot).toMatchSnapshot(file, { maxDiffPixels: 0, threshold: 0 })
        }
      })
      }
    }
  }
}
