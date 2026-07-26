import { expect, test } from '@playwright/test'
import { createFriendPair } from '../src/api.js'
import { WsClient } from '../src/ws-client.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'
const instanceB = process.env.E2E_INSTANCE_B_URL || 'http://127.0.0.1:18082'

test('Redis routing delivers one durable message across two instances', async () => {
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceB })
  const aliceWs = new WsClient(instanceA, pair.alice.token)
  const bobWs = new WsClient(instanceB, pair.bob.token)

  try {
    await Promise.all([aliceWs.connect(), bobWs.connect()])
    const clientMsgId = aliceWs.id('cross-node')
    const content = `cross-instance-${clientMsgId}`
    const aliceCursor = aliceWs.messageCount
    const bobCursor = bobWs.messageCount
    const ack = aliceWs.waitFor(
      (event) => event.event === 'CHAT_ACK' && event.clientMsgId === clientMsgId,
      15_000,
      aliceCursor,
    )
    const delivery = bobWs.waitFor(
      (event) => event.event === 'CHAT_DELIVER' && event.clientMsgId === clientMsgId,
      15_000,
      bobCursor,
    )

    aliceWs.send('CHAT_SEND', {
      toUserId: pair.bob.userId,
      contentType: 'text',
      content,
      isBurn: false,
    }, {
      requestId: aliceWs.id('cross-node-send'),
      clientMsgId,
      conversationId: pair.conversationId,
    })

    const [acknowledged, delivered] = await Promise.all([ack, delivery])
    expect(acknowledged.payload.duplicated).toBe(false)
    expect(delivered.payload.content).toBe(content)
    expect(delivered.conversationId).toBe(pair.conversationId)
    await expect.poll(async () => {
      const history = await pair.bobApi.history(pair.bob.token, pair.conversationId)
      return history.filter((message) => message.clientMsgId === clientMsgId).length
    }).toBe(1)
  } finally {
    aliceWs.close()
    bobWs.close()
  }
})
