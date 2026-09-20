import { expect, test } from '@playwright/test'
import { openFixture } from './fixture.js'

test('production app entry reads generated canvas metadata on initial dark startup', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ status: 503, json: { message: 'Design test: backend unavailable' } }))
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/app/')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0f0f10')
})

for (const theme of ['light', 'dark'] as const) {
  for (const width of [320, 1440]) {
    for (const fontScale of [1, 2]) {
      test(`${theme} ${width} font ${fontScale} keeps primary content and actions reachable`, async ({ page }, info) => {
        await openFixture(page, { theme, width, fontScale })
        const layout = await page.evaluate(() => ({
          viewport: innerWidth, document: document.documentElement.scrollWidth,
          overflow: [...document.querySelectorAll('main, section, label, input, select, button, .message-text, .message-meta')]
            .map(element => ({ tag: element.tagName, className: element.className,
              text: element.textContent?.slice(0, 45), right: element.getBoundingClientRect().right,
              width: element.getBoundingClientRect().width, scrollWidth: element.scrollWidth }))
            .filter(element => element.right > innerWidth + 1),
        }))
        await info.attach('layout', { body: JSON.stringify(layout, null, 2), contentType: 'application/json' })
        console.log(JSON.stringify({ theme, width, fontScale, ...layout }))
        expect(layout.document).toBeLessThanOrEqual(width)
        await expect(page.getByRole('region', { name: '基础控件' }).getByRole('button', { name: '发送消息', exact: true })).toBeVisible()
        await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible()
      })
    }
  }
}

test('theme preference keeps startup system fallback, manual persistence and theme metadata', async ({ page }) => {
  await openFixture(page, { theme: 'dark', stored: null })
  await expect(page.getByTestId('theme')).toHaveText('dark')
  const darkFavicon = await page.locator('link[rel="icon"]').getAttribute('href')
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.getByTestId('theme')).toHaveText('dark') // Existing startup-only system policy.
  await page.getByRole('button', { name: '切换主题' }).click()
  await expect(page.getByTestId('theme')).toHaveText('light')
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#edf0f4')
  const lightFavicon = await page.locator('link[rel="icon"]').getAttribute('href')
  expect(lightFavicon).toBeTruthy()
  expect(lightFavicon).not.toBe(darkFavicon) // Vite may inline SVG assets as data URLs.
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.reload()
  await expect(page.getByTestId('theme')).toHaveText('light')
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', lightFavicon!)
})

test('unsupported stored theme falls back to the initial system theme', async ({ page }) => {
  await openFixture(page, { theme: 'dark', stored: 'unsupported-theme' })
  await expect(page.getByTestId('theme')).toHaveText('dark')
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0f0f10')
})

test('message display consumes supplied states and retry emits the original identity', async ({ page }) => {
  await openFixture(page, { width: 320 })
  const failed = page.locator('.message-row').filter({ hasText: '失败后由原消息发起重试' })
  await expect(failed.locator('.delivery-failed')).toHaveText('发送失败')
  await failed.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByTestId('event')).toHaveText('retry:design-failed')
  await expect(failed).toContainText('发送中')
  await expect(failed.getByRole('button', { name: '重试', exact: true })).toHaveCount(0)
  await expect(page.locator('.message-row')).toHaveCount(7)
  await expect(page.getByText('撤回后不得显示这段内容', { exact: true })).toHaveCount(0)
  await expect(page.getByText('焚毁后不得显示这段内容', { exact: true })).toHaveCount(0)
  await expect(page.locator('.message-placeholder')).toHaveText(['这条消息已撤回', '这条消息已焚毁'])
  await expect(page.locator('.message-row').filter({ hasText: '离线等待连接' })).toContainText('等待连接')
})

test('native controls retain disabled, focus, hover and error-description semantics', async ({ page }) => {
  await openFixture(page)
  const region = page.getByRole('region', { name: '基础控件' })
  const primary = region.getByRole('button', { name: '发送消息', exact: true })
  const before = await primary.evaluate(element => getComputedStyle(element).backgroundColor)
  await primary.hover()
  const hover = await primary.evaluate(element => getComputedStyle(element).backgroundColor)
  expect(hover).not.toBe(before)
  const disabled = region.getByRole('button', { name: '暂不可用' })
  await expect(disabled).toBeDisabled()
  const disabledStyle = await disabled.evaluate(element => getComputedStyle(element).backgroundColor)
  await disabled.hover()
  expect(await disabled.evaluate(element => getComputedStyle(element).backgroundColor)).toBe(disabledStyle)
  await expect(region.getByRole('button', { name: '正在发送…' })).toHaveAttribute('aria-busy', 'true')
  await region.getByRole('button', { name: '添加', exact: true }).focus()
  expect(await page.locator(':focus').evaluate(element => getComputedStyle(element).outlineStyle)).toBe('solid')
  await expect(page.getByLabel('校验示例')).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByLabel('校验示例')).toHaveAccessibleDescription('请核对输入内容。')
})

test('avatar and selected conversation expose named image and current-item semantics', async ({ page }) => {
  await openFixture(page)
  const avatar = page.locator('.fixture-avatar > .avatar')
  const selected = page.locator('.conversation-item--active')
  const semantics = { avatar: await avatar.getAttribute('role'), selected: await selected.getAttribute('aria-current') }
  console.log(JSON.stringify(semantics))
  expect(semantics).toEqual({ avatar: 'img', selected: 'true' })
  await expect(avatar).toHaveAccessibleName('长昵称验证 Long display name的头像，在线')
})

test('an unavailable avatar image falls back to the current nickname without a broken image', async ({ page }) => {
  await page.route('**/app/__avatar_missing__', route => route.fulfill({ status: 404, body: '' }))
  await openFixture(page)
  await page.getByLabel('头像测试地址').fill('/app/__avatar_missing__')
  await expect(page.locator('.fixture-avatar .avatar-letter')).toHaveText('长')
  await expect(page.locator('.fixture-avatar .avatar-image')).toHaveCount(0)
})

test('reduced transparency keeps semantic consumers and old aliases on the same opaque surface', async ({ page }) => {
  await openFixture(page, { theme: 'dark' })
  const session = await page.context().newCDPSession(page)
  await session.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-transparency', value: 'reduce' }] })
  const material = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    return { reduced: matchMedia('(prefers-reduced-transparency: reduce)').matches,
      semantic: style.getPropertyValue('--mx-color-material-surface').trim(),
      legacy: style.getPropertyValue('--surface-glass').trim(), panel: style.getPropertyValue('--panel').trim() }
  })
  expect(material.reduced).toBe(true)
  expect(material.semantic).toBe(material.panel)
  expect(material.legacy).toBe(material.panel)
})
