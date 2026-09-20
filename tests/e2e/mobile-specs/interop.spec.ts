import { expect, test } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

// Actual Vue page and actual backend. The bridge coordinates actions, never substitutes data.
test('existing Web UI exchanges messages with native Flutter clients', async ({ page }) => {
  const configPath = process.env.PROBE_CONFIG
  if (!configPath) throw new Error('PROBE_CONFIG must point to ignored dedicated test credentials')
  const config = JSON.parse(await readFile(configPath, 'utf8')) as Record<string, string>
  const out = path.resolve('../../output/playwright/mx-a05')
  await mkdir(out, { recursive: true })
  page.on('response', response => {
    if (response.status() >= 400) console.log('Web HTTP', response.status(), new URL(response.url()).pathname)
  })
  page.on('websocket', socket => socket.on('framereceived', frame => {
    try { const event = JSON.parse(String(frame.payload)).event;
      if (['TOKEN_EXPIRED', 'FORCE_LOGOUT', 'AUTH_OK'].includes(event)) console.log('Web WS', event)
    } catch { /* Non-JSON frames are not logged. */ }
  }))
  await page.goto('/app/')
  await page.getByPlaceholder('例如 atti_20').fill(config.PROBE_PEER_USERNAME!)
  await page.getByPlaceholder('输入账号密码').fill(config.PROBE_PEER_PASSWORD!)
  await page.getByRole('button', { name: '登录 MeshX', exact: true }).click()
  await page.getByText('MeshX 产品讨论', { exact: true }).first().click({ timeout: 30_000 })
  await expect(page.locator('textarea')).toBeVisible()
  await writeFile(path.join(out, 'web-ready.txt'), await page.locator('body').ariaSnapshot())
  await page.screenshot({ path: path.join(out, 'web-ready.png'), fullPage: true })
  console.log('Real Web client ready for Flutter interop')
  const completed: { id: string; action: string; ok: boolean }[] = []
  for (;;) {
    const command = await fetch('http://127.0.0.1:18386/next').then(r => r.json()) as
      { id?: string; action?: string; text?: string }
    if (!command.id) { await page.waitForTimeout(500); continue }
    console.log('Browser command', command.id, command.action)
    await writeFile(path.join(out, 'web-current.txt'), await page.locator('body').ariaSnapshot())
    let failure: string | undefined
    try {
      if (command.action === 'finish') {
        await fetch('http://127.0.0.1:18386/result', { method: 'POST', body: JSON.stringify({ id: command.id, ok: true }) })
        break
      }
      if (command.action === 'send') {
        await page.locator('textarea').fill(command.text!)
        await page.getByRole('button', { name: '发送消息', exact: true }).click()
      }
      await expect(page.getByText(command.text!, { exact: true }).last()).toBeVisible({ timeout: 30_000 })
      await page.screenshot({ path: path.join(out, `web-command-${command.id}.png`), fullPage: true })
      completed.push({ id: command.id, action: command.action!, ok: true })
    } catch (error) {
      await page.screenshot({ path: path.join(out, 'web-failure.png'), fullPage: true })
      await writeFile(path.join(out, 'web-failure.txt'), await page.locator('body').ariaSnapshot())
      failure = error instanceof Error ? error.message : String(error)
      completed.push({ id: command.id, action: command.action!, ok: false })
    }
    await writeFile(path.join(out, 'interop-results.json'), JSON.stringify(completed, null, 2))
    await fetch('http://127.0.0.1:18386/result', { method: 'POST', body: JSON.stringify({ id: command.id, ok: !failure, error: failure }) })
    if (failure) throw new Error(failure)
  }
  expect(completed.length).toBeGreaterThan(0)
})
