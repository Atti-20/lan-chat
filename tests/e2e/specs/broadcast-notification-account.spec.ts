import { expect, test } from '@playwright/test'
import { ApiClient, createUser, uniqueId } from '../src/api.js'
import { WsClient, type WsEnvelope } from '../src/ws-client.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'
const instanceB = process.env.E2E_INSTANCE_B_URL || 'http://127.0.0.1:18082'
const adminPassword = process.env.LANCHAT_BOOTSTRAP_ADMIN_PASSWORD || 'E2eAdminPassword-2026'

type BroadcastNoticeKind = 'BROADCAST_OVERVIEW' | 'BROADCAST_REMINDER'

interface BroadcastNoticeCard {
  kind: BroadcastNoticeKind
  broadcastId: number
  noticeGeneration: number
  reminderSequence?: number
  title: string
}

type MessageRecord = Record<string, unknown>

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function numberValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function noticeCard(message: unknown): BroadcastNoticeCard | null {
  if (!isRecord(message) || typeof message.content !== 'string') return null
  try {
    const card = JSON.parse(message.content) as Partial<BroadcastNoticeCard>
    if (card.kind !== 'BROADCAST_OVERVIEW' && card.kind !== 'BROADCAST_REMINDER') return null
    if (!Number.isInteger(card.broadcastId) || !Number.isInteger(card.noticeGeneration)) return null
    return card as BroadcastNoticeCard
  } catch {
    return null
  }
}

function messagesFromSync(envelope: WsEnvelope): MessageRecord[] {
  const values = Array.isArray(envelope.payload.messages) ? envelope.payload.messages : []
  return values.filter(isRecord)
}

async function syncNotice(
  socket: WsClient,
  kind: BroadcastNoticeKind,
  broadcastId: number,
): Promise<{ card: BroadcastNoticeCard; message: MessageRecord }> {
  const sync = await socket.syncAll()
  const message = messagesFromSync(sync).find((item) => {
    const card = noticeCard(item)
    return card?.kind === kind && card.broadcastId === broadcastId
  })
  const card = noticeCard(message)
  expect(card).not.toBeNull()
  return { card: card!, message: message! }
}

function isBroadcastRefresh(event: WsEnvelope, broadcastId: number): boolean {
  return event.event === 'BROADCAST'
    && numberValue(event.payload.broadcastId) === broadcastId
}

function conversationIdFrom(message: MessageRecord): string {
  return typeof message.conversationId === 'string' ? message.conversationId : ''
}

test('broadcast notification account persists one overview and one manual reminder before any realtime refresh', async () => {
  test.setTimeout(90_000)
  const recipientApi = new ApiClient(instanceA)
  const recipient = await createUser(recipientApi, 'e2e_notice_receiver')
  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login('admin', adminPassword, 'LANChat E2E broadcaster')
  const receiverSocket = new WsClient(instanceA, recipient.session.token)

  try {
    await receiverSocket.connect()
    const beforeCreate = receiverSocket.messageCount
    const title = `Notice E2E ${uniqueId('broadcast')}`
    const broadcast = await adminApi.createBroadcast(admin.token, {
      title,
      content: 'The technical-account card and its refresh intent must commit together.',
      scopeType: 'ALL',
      confirmationRequired: true,
      confirmationOptions: ['EXECUTED'],
    })

    const refresh = await receiverSocket.waitFor(
      (event) => isBroadcastRefresh(event, broadcast.id),
      15_000,
      beforeCreate,
    )
    expect(refresh.payload).toEqual({ broadcastId: broadcast.id })

    const overview = await syncNotice(receiverSocket, 'BROADCAST_OVERVIEW', broadcast.id)
    expect(overview.card.title).toBe(title)
    const conversationId = conversationIdFrom(overview.message)
    expect(conversationId).toMatch(/^private:\d+:\d+$/)

    const beforeReminder = receiverSocket.messageCount
    await adminApi.request<void>(
      `/broadcast/${broadcast.id}/receivers/${recipient.session.userId}/remind`,
      { method: 'POST', token: admin.token },
    )
    await receiverSocket.waitFor(
      (event) => isBroadcastRefresh(event, broadcast.id),
      15_000,
      beforeReminder,
    )
    const reminder = await syncNotice(receiverSocket, 'BROADCAST_REMINDER', broadcast.id)
    expect(reminder.card.reminderSequence).toBe(1)
    expect(receiverSocket.countWhere((event) => event.event === 'BROADCAST_REMINDER')).toBe(0)
    expect(receiverSocket.countWhere((event) => event.event === 'CHAT_DELIVER')).toBe(0)

    const history = await recipientApi.history(recipient.session.token, conversationId)
    const cards = history.map(noticeCard).filter((card): card is BroadcastNoticeCard => card?.broadcastId === broadcast.id)
    expect(cards.filter((card) => card.kind === 'BROADCAST_OVERVIEW')).toHaveLength(1)
    expect(cards.filter((card) => card.kind === 'BROADCAST_REMINDER')).toHaveLength(1)
  } finally {
    await receiverSocket.disconnect()
  }
})

test('an offline recipient reads the committed overview after authentication without a server-side replay write', async () => {
  test.setTimeout(90_000)
  const recipientApi = new ApiClient(instanceA)
  const recipient = await createUser(recipientApi, 'e2e_notice_offline')
  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login('admin', adminPassword, 'LANChat E2E offline broadcaster')
  const receiverSocket = new WsClient(instanceA, recipient.session.token)

  try {
    const title = `Offline notice ${uniqueId('broadcast')}`
    const broadcast = await adminApi.createBroadcast(admin.token, {
      title,
      content: 'A persistent card must be available to an offline receiver on its first sync.',
      scopeType: 'ALL',
      confirmationRequired: true,
      confirmationOptions: ['EXECUTED'],
    })

    await receiverSocket.connect()
    const overview = await syncNotice(receiverSocket, 'BROADCAST_OVERVIEW', broadcast.id)
    expect(overview.card.title).toBe(title)
    const conversationId = conversationIdFrom(overview.message)
    expect(conversationId).toMatch(/^private:\d+:\d+$/)

    const history = await recipientApi.history(recipient.session.token, conversationId)
    const recoveredCards = history.map(noticeCard).filter((card): card is BroadcastNoticeCard => card?.broadcastId === broadcast.id)
    expect(recoveredCards.filter((card) => card.kind === 'BROADCAST_OVERVIEW')).toHaveLength(1)
  } finally {
    await receiverSocket.disconnect()
  }
})

test('desktop recovery reads the same persisted overview without replacing the active web device', async () => {
  test.setTimeout(90_000)
  const recipientApiA = new ApiClient(instanceA)
  const recipient = await createUser(recipientApiA, 'e2e_notice_multidevice')
  const recipientApiB = new ApiClient(instanceB)
  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login('admin', adminPassword, 'LANChat E2E multi-device broadcaster')
  const firstSocket = new WsClient(instanceA, recipient.session.token)
  let desktopSocket: WsClient | undefined

  try {
    await firstSocket.connect()
    const broadcast = await adminApi.createBroadcast(admin.token, {
      title: `Multi-device notice ${uniqueId('broadcast')}`,
      content: 'A desktop login must not evict the distinct web device.',
      scopeType: 'ALL',
      confirmationRequired: true,
      confirmationOptions: ['EXECUTED'],
    })
    await firstSocket.waitFor((event) => isBroadcastRefresh(event, broadcast.id), 15_000)
    const firstOverview = await syncNotice(firstSocket, 'BROADCAST_OVERVIEW', broadcast.id)

    const desktopSession = await recipientApiB.login(
      recipient.username,
      recipient.password,
      'LANChat E2E desktop recovery',
      'desktop',
    )
    desktopSocket = new WsClient(instanceB, desktopSession.token)
    await desktopSocket.connect()
    expect(firstSocket.isOpen).toBe(true)

    const desktopOverview = await syncNotice(desktopSocket, 'BROADCAST_OVERVIEW', broadcast.id)
    expect(desktopOverview.card.title).toBe(firstOverview.card.title)
    await new Promise((resolve) => setTimeout(resolve, 500))
    expect(firstSocket.isOpen).toBe(true)
    expect(firstSocket.countWhere((event) => event.event === 'FORCE_LOGOUT')).toBe(0)

    const conversationId = conversationIdFrom(desktopOverview.message)
    const history = await recipientApiB.history(desktopSession.token, conversationId)
    const overviewCards = history.map(noticeCard).filter((card): card is BroadcastNoticeCard =>
      card?.kind === 'BROADCAST_OVERVIEW' && card.broadcastId === broadcast.id)
    expect(overviewCards).toHaveLength(1)
  } finally {
    await desktopSocket?.disconnect()
    await firstSocket.disconnect()
  }
})

test('removing a target atomically redacts its card and sends only a durable recall frame', async () => {
  test.setTimeout(90_000)
  const recipientApi = new ApiClient(instanceA)
  const recipient = await createUser(recipientApi, 'e2e_notice_removed')
  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login('admin', adminPassword, 'LANChat E2E target editor')
  const socket = new WsClient(instanceA, recipient.session.token)

  try {
    await socket.connect()
    const broadcast = await adminApi.createBroadcast(admin.token, {
      title: `Removal notice ${uniqueId('broadcast')}`,
      content: 'Removing a recipient must redact this technical-account card in the same transaction.',
      scopeType: 'ALL',
      confirmationRequired: true,
      confirmationOptions: ['EXECUTED'],
    })
    await socket.waitFor((event) => isBroadcastRefresh(event, broadcast.id), 15_000)
    const overview = await syncNotice(socket, 'BROADCAST_OVERVIEW', broadcast.id)
    const conversationId = conversationIdFrom(overview.message)
    const messageId = typeof overview.message.messageId === 'string' ? overview.message.messageId : ''
    expect(messageId).not.toBe('')

    const cursor = socket.messageCount
    await adminApi.request<void>(`/broadcast/${broadcast.id}/receivers`, {
      method: 'PATCH',
      token: admin.token,
      body: { removeUserIds: [recipient.session.userId] },
    })
    const recall = await socket.waitFor(
      (event) => event.event === 'CHAT_RECALL' && event.payload.messageId === messageId,
      15_000,
      cursor,
    )
    expect(recall.payload).toEqual({ messageId })

    const history = await recipientApi.history(recipient.session.token, conversationId)
    const redacted = history.find((message) => message.messageId === messageId)
    expect(redacted?.isRecalled).toBe(1)
    expect(redacted?.content).toBe('')
  } finally {
    await socket.disconnect()
  }
})
