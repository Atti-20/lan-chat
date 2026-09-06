import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

async function loadDeduperModule() {
  const [typescriptModule, deduperSource] = await Promise.all([
    import('../node_modules/typescript/lib/typescript.js'),
    source('../src/platform/notificationDeliveryDeduper.ts'),
  ])
  const typescript = typescriptModule.default || typescriptModule
  const compiled = typescript.transpileModule(deduperSource, {
    compilerOptions: {
      module: typescript.ModuleKind.ESNext,
      target: typescript.ScriptTarget.ES2022,
    },
  }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
}

class MemoryStorage {
  records = new Map()

  getItem(key) {
    return this.records.get(key) || null
  }

  setItem(key, value) {
    this.records.set(key, value)
  }

  removeItem(key) {
    this.records.delete(key)
  }
}

test('recovered messages are grouped by conversation before scheduling a local notification', async () => {
  const chat = await source('../src/composables/useChat.ts')

  assert.match(chat, /const syncNotificationCandidates = new Map<string, ChatMessage>\(\)/)
  assert.match(chat, /const unreadDelta = Math\.max\(0, after - before\)/)
  assert.match(chat, /if \(unreadDelta > 0 && isIncomingMessage\)/)
  assert.match(chat, /syncNotificationCandidates\.set\(message\.conversationId, message\)/)
  assert.match(chat, /syncNotificationCandidates\.forEach\(\(message, conversationId\) => \{\s*scheduleIncomingNotification/s)
  assert.match(chat, /registerSystemNotificationAccount\(message\)/)
})

test('message notification delivery uses a stable business key and preserves broadcast context', async () => {
  const chat = await source('../src/composables/useChat.ts')

  assert.match(chat, /const isDuplicateDelivery = delivered\.sequence != null\s*&& delivered\.sequence <= previousSequence/s)
  assert.match(chat, /else if \(isIncomingMessage && !isDuplicateDelivery\)/)
  assert.match(chat, /if \(!isDuplicateDelivery\) \{\s*scheduleIncomingNotification\(delivered/s)
  assert.match(chat, /dedupeKey: messageNotificationDedupeKey\(message\)/)
  assert.match(chat, /'message',\s*selectedNode\(\)\?\.origin \|\| 'unknown-node',\s*currentUser\.value\?\.id \|\| 'anonymous'/s)
  assert.match(chat, /kind === 'BROADCAST_REMINDER' \? '广播提醒' : '广播通知'/)
  assert.match(chat, /priority === 'EMERGENCY'\s*\? '紧急广播'/)
})

test('native bridges persist de-duplication only after permission checks and release failed schedules', async () => {
  const bridge = await source('../src/platform/nativeBridge.ts')
  const deduper = await source('../src/platform/notificationDeliveryDeduper.ts')

  assert.match(bridge, /dedupeKey\?: string/)
  assert.match(bridge, /const notificationDeliveryDeduper = new NotificationDeliveryDeduper\(\)/)
  assert.match(bridge, /notificationDeliveryDeduper\.accept\(dedupeKey\)/)
  assert.match(bridge, /notificationDeliveryDeduper\.forget\(dedupeKey\)/)
  assert.match(deduper, /const DEFAULT_TTL_MS = 30 \* 24 \* 60 \* 60 \* 1_000/)
  assert.match(deduper, /Only opaque message identifiers are persisted; titles/)
  assert.match(deduper, /while \(records\.length > this\.maxEntries\) records\.shift\(\)/)
})

test('notification delivery de-duplication persists, expires, bounds storage, and can release a failed schedule', async () => {
  const { NotificationDeliveryDeduper } = await loadDeduperModule()
  const storage = new MemoryStorage()
  let now = 10_000
  const createDeduper = () => new NotificationDeliveryDeduper(
    () => storage,
    () => now,
    100,
    2,
    'test-notification-keys',
  )

  const firstRun = createDeduper()
  assert.equal(firstRun.accept('message-a'), true)
  assert.equal(firstRun.accept('message-a'), false)
  assert.equal(firstRun.accept('message-b'), true)
  assert.equal(firstRun.accept('message-c'), true)

  const restarted = createDeduper()
  assert.equal(restarted.accept('message-c'), false)
  assert.equal(restarted.accept('message-a'), true)
  restarted.forget('message-a')
  assert.equal(restarted.accept('message-a'), true)

  now += 101
  const afterExpiry = createDeduper()
  assert.equal(afterExpiry.accept('message-c'), true)
})

test('the broadcast refresh event does not create a second native alert beside its technical-account card', async () => {
  const broadcasts = await source('../src/composables/useBroadcasts.ts')

  assert.doesNotMatch(broadcasts, /nativeBridge\.notify/)
  assert.match(broadcasts, /technical notification account's CHAT_DELIVER is the canonical/)
})

test('selected but hidden conversations keep unread counts and cannot start read or burn side effects', async () => {
  const chat = await source('../src/composables/useChat.ts')
  const bridge = await source('../src/platform/nativeBridge.ts')
  assert.match(chat, /updateConversationPreview\(delivered, isCurrentConversation && canReadSelectedConversation\(conversationId\)\)/)
  assert.match(chat, /updateConversationPreview\(message, isCurrentConversation && canReadSelectedConversation\(message.conversationId\)\)/)
  assert.match(chat, /if \(isIncomingMessage && canReadSelectedConversation\(conversationId\)\) \{\s*startBurnCountdown/)
  assert.match(chat, /function sendReadPosition[^]*?if \(!canReadSelectedConversation\(conversationId\)\) return/)
  assert.match(chat, /function scheduleBurnCountdowns[^]*?if \(!canReadSelectedConversation\(\)\) return/)
  assert.match(chat, /document.addEventListener\('visibilitychange', acknowledgeVisibleConversation\)/)
  assert.match(bridge, /initializeMobileNotification\(\{ requestPermission: false \}\)/)
})
