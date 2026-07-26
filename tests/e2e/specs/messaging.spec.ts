import { expect, test } from '@playwright/test'
import { createFriendPair } from '../src/api.js'
import { WsClient, type WsEnvelope } from '../src/ws-client.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'

function sendText(
  sender: WsClient,
  pair: Awaited<ReturnType<typeof createFriendPair>>,
  content: string,
  clientMsgId = sender.id('client'),
): { clientMsgId: string; ack: Promise<WsEnvelope> } {
  const cursor = sender.messageCount
  const ack = sender.waitFor(
    (envelope) => envelope.event === 'CHAT_ACK'
      && envelope.clientMsgId === clientMsgId,
    15_000,
    cursor,
  )
  sender.send('CHAT_SEND', {
    toUserId: pair.bob.userId,
    contentType: 'text',
    content,
    isBurn: false,
  }, {
    requestId: sender.id('send'),
    clientMsgId,
    conversationId: pair.conversationId,
  })
  return { clientMsgId, ack }
}

test('private chat persists and delivers a message on one instance', async () => {
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceA })
  const aliceWs = new WsClient(instanceA, pair.alice.token)
  const bobWs = new WsClient(instanceA, pair.bob.token)

  try {
    await Promise.all([aliceWs.connect(), bobWs.connect()])
    const content = `private-${aliceWs.id('content')}`
    const bobCursor = bobWs.messageCount
    const delivery = bobWs.waitFor(
      (event) => event.event === 'CHAT_DELIVER' && event.payload.content === content,
      15_000,
      bobCursor,
    )
    const sent = sendText(aliceWs, pair, content)
    const [ack, delivered] = await Promise.all([sent.ack, delivery])

    expect(ack.payload.duplicated).toBe(false)
    expect(delivered.conversationId).toBe(pair.conversationId)
    expect(delivered.payload.fromUserId).toBe(pair.alice.userId)
    await expect.poll(async () => {
      const history = await pair.bobApi.history(pair.bob.token, pair.conversationId)
      return history.find((message) => message.clientMsgId === sent.clientMsgId)?.content
    }).toBe(content)
  } finally {
    aliceWs.close()
    bobWs.close()
  }
})

test('group chat fans out to a member and persists group history', async () => {
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceA })
  const group = await pair.aliceApi.createGroup(
    pair.alice.token,
    `E2E group ${Date.now().toString(36)}`.slice(0, 20),
    [pair.bob.userId],
  )
  const conversationId = `group:${group.id}`
  const aliceWs = new WsClient(instanceA, pair.alice.token)
  const bobWs = new WsClient(instanceA, pair.bob.token)

  try {
    await Promise.all([aliceWs.connect(), bobWs.connect()])
    const clientMsgId = aliceWs.id('group')
    const content = `group-message-${clientMsgId}`
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
      groupId: group.id,
      contentType: 'text',
      content,
      isBurn: false,
    }, {
      requestId: aliceWs.id('send-group'),
      clientMsgId,
      conversationId,
    })

    const [acknowledged, delivered] = await Promise.all([ack, delivery])
    expect(acknowledged.payload.duplicated).toBe(false)
    expect(delivered.conversationId).toBe(conversationId)
    expect(delivered.payload.content).toBe(content)
    await expect.poll(async () => {
      const history = await pair.bobApi.history(pair.bob.token, conversationId)
      return history.filter((message) => message.clientMsgId === clientMsgId).length
    }).toBe(1)
  } finally {
    aliceWs.close()
    bobWs.close()
  }
})

test('CHAT_ACK makes a retried clientMsgId idempotent', async () => {
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceA })
  const aliceWs = new WsClient(instanceA, pair.alice.token)

  try {
    await aliceWs.connect()
    const clientMsgId = aliceWs.id('idempotent')
    const content = `idempotent-${clientMsgId}`
    const first = sendText(aliceWs, pair, content, clientMsgId)
    const firstAck = await first.ack
    expect(firstAck.payload.duplicated).toBe(false)

    const retryCursor = aliceWs.messageCount
    const duplicateAck = aliceWs.waitFor(
      (event) => event.event === 'CHAT_ACK'
        && event.clientMsgId === clientMsgId
        && event.payload.duplicated === true,
      15_000,
      retryCursor,
    )
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

    expect(retried.payload.messageId).toBe(firstAck.payload.messageId)
    expect(retried.payload.sequence).toBe(firstAck.payload.sequence)
    await expect.poll(async () => {
      const history = await pair.aliceApi.history(pair.alice.token, pair.conversationId)
      return history.filter((message) => message.clientMsgId === clientMsgId).length
    }).toBe(1)
  } finally {
    aliceWs.close()
  }
})
