import { expect, test } from '@playwright/test'
import { createFriendPair } from '../src/api.js'
import { WsClient } from '../src/ws-client.js'

test('browser outbox delivers a queued text after network recovery', async ({
  context,
  page,
}) => {
  const pair = await createFriendPair()
  const instanceB = process.env.E2E_INSTANCE_B_URL
    || 'http://127.0.0.1:18082'
  const bobWs = new WsClient(instanceB, pair.bob.token)
  await bobWs.connect()

  try {
    await page.addInitScript((session) => {
      sessionStorage.setItem('lanchat_session_v2', JSON.stringify(session))
    }, pair.alice)
    await page.goto('/app/')

    const conversation = page.locator('button.conversation-item')
      .filter({ hasText: pair.bob.nickname })
    await expect(conversation).toBeVisible()
    await conversation.click()

    const composer = page.locator('textarea')
    await expect(composer).toHaveAttribute('placeholder', /发消息给/)

    await context.setOffline(true)
    await expect(composer).toHaveAttribute(
      'placeholder',
      /离线消息将保存在本机/,
    )

    const content = `offline-recovery-${Date.now().toString(36)}`
    const delivery = bobWs.waitFor((envelope) =>
      envelope.event === 'CHAT_DELIVER'
      && envelope.payload.content === content, 30_000)
    await composer.fill(content)
    await page.getByRole('button', { name: '发送消息' }).click()
    await expect(page.getByText(content)).toBeVisible()
    await expect(page.getByText(/待发送 1/).first()).toBeVisible()

    await context.setOffline(false)
    const delivered = await delivery
    expect(delivered.conversationId).toBe(pair.conversationId)

    await expect.poll(async () => {
      const history = await pair.aliceApi.history(
        pair.alice.token,
        pair.conversationId,
      )
      return history.filter((item) => item.content === content).length
    }).toBe(1)
  } finally {
    bobWs.close()
    await context.setOffline(false)
  }
})
