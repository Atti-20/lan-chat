import { expect, test } from '@playwright/test'
import { createFriendPair } from '../src/api.js'
import { restartComposeService, waitForHealthyHttp } from '../src/compose.js'
import { WsClient } from '../src/ws-client.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'
const instanceB = process.env.E2E_INSTANCE_B_URL || 'http://127.0.0.1:18082'

test('client reconnects after instance restart and SYNC returns the missed message', async () => {
  test.setTimeout(180_000)
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceB })
  const aliceBeforeRestart = new WsClient(instanceA, pair.alice.token)
  const bobWs = new WsClient(instanceB, pair.bob.token)
  let aliceAfterRestart: WsClient | undefined

  try {
    await Promise.all([aliceBeforeRestart.connect(), bobWs.connect()])

    const baselineId = bobWs.id('baseline')
    const baselineContent = `before-restart-${baselineId}`
    const baselineAliceCursor = aliceBeforeRestart.messageCount
    const baselineBobCursor = bobWs.messageCount
    const baselineDelivery = aliceBeforeRestart.waitFor(
      (event) => event.event === 'CHAT_DELIVER' && event.clientMsgId === baselineId,
      15_000,
      baselineAliceCursor,
    )
    const baselineAck = bobWs.waitFor(
      (event) => event.event === 'CHAT_ACK' && event.clientMsgId === baselineId,
      15_000,
      baselineBobCursor,
    )
    bobWs.send('CHAT_SEND', {
      toUserId: pair.alice.userId,
      contentType: 'text',
      content: baselineContent,
      isBurn: false,
    }, {
      requestId: bobWs.id('baseline-send'),
      clientMsgId: baselineId,
      conversationId: pair.conversationId,
    })
    const [baselineAcknowledged] = await Promise.all([baselineAck, baselineDelivery])
    const baselineSequence = Number(baselineAcknowledged.payload.sequence)
    expect(baselineSequence).toBeGreaterThan(0)

    const closedByRestart = aliceBeforeRestart.waitForClose(60_000)
    const restart = restartComposeService('lanchat')
    const close = await closedByRestart
    expect(close.code).not.toBe(1000)

    const missedId = bobWs.id('missed')
    const missedContent = `during-restart-${missedId}`
    const missedBobCursor = bobWs.messageCount
    const missedAck = bobWs.waitFor(
      (event) => event.event === 'CHAT_ACK' && event.clientMsgId === missedId,
      30_000,
      missedBobCursor,
    )
    bobWs.send('CHAT_SEND', {
      toUserId: pair.alice.userId,
      contentType: 'text',
      content: missedContent,
      isBurn: false,
    }, {
      requestId: bobWs.id('missed-send'),
      clientMsgId: missedId,
      conversationId: pair.conversationId,
    })
    const missedAcknowledged = await missedAck
    expect(Number(missedAcknowledged.payload.sequence)).toBeGreaterThan(baselineSequence)

    await restart
    await waitForHealthyHttp(`${instanceA}/api/v1/node/health`)

    aliceAfterRestart = new WsClient(instanceA, pair.alice.token)
    await aliceAfterRestart.connect()
    const syncRequestId = aliceAfterRestart.id('sync')
    const syncCursor = aliceAfterRestart.messageCount
    const sync = aliceAfterRestart.waitFor(
      (event) => event.event === 'SYNC_RESPONSE' && event.requestId === syncRequestId,
      30_000,
      syncCursor,
    )
    aliceAfterRestart.send('SYNC_REQUEST', {
      positions: { [pair.conversationId]: baselineSequence },
      limit: 100,
    }, { requestId: syncRequestId })

    const response = await sync
    const messages = response.payload.messages as Array<Record<string, unknown>>
    expect(messages).toEqual(expect.arrayContaining([
      expect.objectContaining({
        clientMsgId: missedId,
        conversationId: pair.conversationId,
        content: missedContent,
      }),
    ]))
    expect(response.payload.deniedConversationIds).toEqual([])
  } finally {
    aliceBeforeRestart.close()
    aliceAfterRestart?.close()
    bobWs.close()
    // Even when an assertion races the health transition, leave instance A
    // available for the remaining serial E2E cases.
    await waitForHealthyHttp(`${instanceA}/api/v1/node/health`, 120_000)
      .catch(async () => {
        await restartComposeService('lanchat')
        await waitForHealthyHttp(`${instanceA}/api/v1/node/health`, 120_000)
      })
  }
})
