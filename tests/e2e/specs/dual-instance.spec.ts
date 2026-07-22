import { expect, test } from '@playwright/test'
import { createFriendPair } from '../src/api.js'
import { WsClient } from '../src/ws-client.js'

test('routes one durable chat message across two real instances', async () => {
  const pair = await createFriendPair()
  const instanceA = process.env.E2E_INSTANCE_A_URL
    || 'http://127.0.0.1:18081'
  const instanceB = process.env.E2E_INSTANCE_B_URL
    || 'http://127.0.0.1:18082'
  const aliceWs = new WsClient(instanceA, pair.alice.token)
  const bobWs = new WsClient(instanceB, pair.bob.token)

  try {
    await Promise.all([aliceWs.connect(), bobWs.connect()])
    const clientMsgId = aliceWs.id('client')
    const content = `cross-instance-${clientMsgId}`
    const firstAck = aliceWs.waitFor((envelope) =>
      envelope.event === 'CHAT_ACK'
      && envelope.clientMsgId === clientMsgId)
    const delivery = bobWs.waitFor((envelope) =>
      envelope.event === 'CHAT_DELIVER'
      && envelope.clientMsgId === clientMsgId)

    aliceWs.send('CHAT_SEND', {
      toUserId: pair.bob.userId,
      contentType: 'text',
      content,
      isBurn: false,
    }, {
      requestId: aliceWs.id('send'),
      clientMsgId,
      conversationId: pair.conversationId,
    })

    const [ack, delivered] = await Promise.all([firstAck, delivery])
    expect(ack.payload.duplicated).toBe(false)
    expect(delivered.payload.content).toBe(content)
    expect(delivered.conversationId).toBe(pair.conversationId)

    const duplicateAck = aliceWs.waitFor((envelope) =>
      envelope.event === 'CHAT_ACK'
      && envelope.clientMsgId === clientMsgId
      && envelope.payload.duplicated === true)
    aliceWs.send('CHAT_SEND', {
      toUserId: pair.bob.userId,
      contentType: 'text',
      content,
      isBurn: false,
    }, {
      requestId: aliceWs.id('retry'),
      clientMsgId,
      conversationId: pair.conversationId,
    })
    const retried = await duplicateAck
    expect(retried.payload.messageId).toBe(ack.payload.messageId)
    expect(retried.payload.sequence).toBe(ack.payload.sequence)

    await expect.poll(async () => {
      const history = await pair.bobApi.history(
        pair.bob.token,
        pair.conversationId,
      )
      return history.filter((item) => item.clientMsgId === clientMsgId).length
    }).toBe(1)
  } finally {
    aliceWs.close()
    bobWs.close()
  }
})
