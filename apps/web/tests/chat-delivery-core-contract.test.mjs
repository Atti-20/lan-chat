import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import ts from 'typescript'
import { createTsLoader, repositoryRoot, plain } from './helpers/load-ts.mjs'

const vectors = JSON.parse(readFileSync(`${repositoryRoot}/contracts/fixtures/core-v1.json`, 'utf8'))
const frame = id => structuredClone(vectors.frames.find(value => value.id === id).frame)
const source = readFileSync(`${repositoryRoot}/apps/web/src/composables/useChat.ts`, 'utf8')
const ast = ts.createSourceFile('useChat.ts', source, ts.ScriptTarget.Latest, true)
const names = new Set(['handleAck', 'handleDelivery', 'recordPosition', 'recordReceivedPositions', 'recordSynchronizedPositions',
  'mergeCurrentMessages', 'mergeMessages', 'normalizeMessage', 'belongsToSelected', 'flushOutbox', 'updateDeliveryState'])
const bodies = []
function collect(node) {
  if (ts.isFunctionDeclaration(node) && names.has(node.name?.text)) bodies.push(node.getText(ast))
  ts.forEachChild(node, collect)
}
collect(ast); assert.equal(bodies.length, names.size)
const compiled = ts.transpileModule(bodies.join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

function harness(cursor = 41) {
  const stored = new Map(), cached = new Map(), persisted = new Map(), requests = [], notifications = []
  const messages = vue.ref([]), runtimePositions = new Map([['private:7:8', cursor]])
  const queueStorage = {
    loadOutbox: async () => [...stored.values()],
    saveOutboxEntry: async entry => { stored.set(entry.clientMsgId, plain(entry)) },
    deleteOutboxEntry: async id => { stored.delete(id) },
  }
  const load = createTsLoader({ externals: { vue }, replacements: { 'apps/web/src/services/localChatDb.ts': queueStorage } })
  const rules = load('packages/domain-ts/src/messages.ts')
  const outbox = load('apps/web/src/composables/useOutbox.ts').useOutbox()
  let syncs = 0, ack = async () => 'TIMEOUT'
  const dependencies = {
    recoveryEnabled: { value: false },
    currentUser: { value: { id: 7 } }, selected: { value: { conversationId: 'private:7:8' } },
    messages, runtimePositions, outbox, conversations: { value: [] },
    normalizeChatMessage: rules.normalizeMessage, mergeChatMessages: rules.mergeMessages, sortMessages: rules.sortMessages,
    advanceContiguousSequence: load('packages/domain-ts/src/sequence.ts').advanceContiguousSequence,
    inaccessibleConversationIds: new Set(), recalledMessageIds: new Set(),
    rememberRecalledMessage() {}, registerSystemNotificationAccount() {},
    async cacheMessages(values) { for (const value of values) cached.set(`${value.conversationId}:${value.messageId}`, plain(value)) },
    async deleteCachedMessagesByClientMsgId(id) { for (const [key, value] of cached) if (value.clientMsgId === id) cached.delete(key) },
    async savePosition(id, value) { persisted.set(id, Math.max(persisted.get(id) || 0, value)) },
    async loadPositions() { return Object.fromEntries(persisted) },
    settleAck() {}, updateConversationPreview() {}, canReadSelectedConversation: () => true,
    startBurnCountdown() {}, sendReadPosition() {}, toast: { push() {} }, playNotificationSound() {}, conversationPreview: () => '',
    scheduleIncomingNotification: value => notifications.push(value.messageId),
    ws: { connected: { value: true }, sendEvent: (event, payload, metadata) => { requests.push({ event, payload, metadata }); return true } },
    createRequestId: () => 'test-request', refreshAndSynchronize: async () => { syncs++ },
    flushPromise: null, waitForAck: (...args) => ack(...args),
  }
  const handlers = new Function(...Object.keys(dependencies), `${compiled}; return { ${[...names].join(',')} };`)(...Object.values(dependencies))
  return { ...handlers, messages, outbox, stored, cached, persisted, requests, notifications, runtimePositions,
    get syncs() { return syncs }, set ack(value) { ack = value } }
}

async function enqueue(h) {
  const outgoing = frame('duplicate-client-msg-id')
  await h.outbox.enqueue({ clientMsgId: outgoing.clientMsgId, requestId: outgoing.requestId, conversationId: 'private:7:8',
    payload: outgoing.payload, createdAt: '2026-09-08T23:00:00', retryCount: 0, state: 'WAITING_NETWORK' })
  h.messages.value = [{ ...frame('event-after-ack').payload, messageId: 'local:client-msg-0001', sequence: undefined, deliveryState: 'SENDING' }]
}

test('shared ACK/repeated-delivery vectors reconcile one message, clear outbox and do not notify twice', async () => {
  const h = harness()
  await enqueue(h)
  await h.handleAck(frame('ack-after-retry'))
  await h.handleAck(frame('ack-after-retry'))
  await h.handleDelivery(frame('event-after-ack')); await h.handleDelivery(frame('event-after-ack'))
  assert.equal(h.messages.value.length, 1); assert.equal(h.cached.size, 1)
  assert.equal(h.messages.value[0].messageId, 'server-msg-0001')
  assert.equal(h.outbox.entries.value.length, 0); assert.equal(h.stored.size, 0)
  assert.equal(h.runtimePositions.get('private:7:8'), 42)
  assert.deepEqual(h.notifications, [])
})

test('a sequence gap requests sync without advancing a receive cursor; authoritative sync then closes it', async () => {
  const h = harness(40)
  await h.handleDelivery(frame('event-after-ack'))
  assert.equal(h.syncs, 1)
  assert.equal(h.runtimePositions.get('private:7:8'), 40)
  await h.recordReceivedPositions([frame('event-after-ack').payload])
  assert.equal(h.runtimePositions.get('private:7:8'), 40)
  const sync = frame('reconnect-sync-response').payload
  await h.recordSynchronizedPositions(sync.messages, sync.latestPositions)
  assert.equal(h.runtimePositions.get('private:7:8'), 42)
  await h.handleDelivery(frame('event-after-ack'))
  assert.equal(h.syncs, 1); assert.equal(h.messages.value.length, 1)
})

test('outbox timeout retries keep original request/client IDs and an eventual ACK removes the entry', async () => {
  const h = harness(); await enqueue(h)
  let attempts = 0
  h.ack = async () => {
    if (++attempts === 1) return 'TIMEOUT'
    await h.handleAck(frame('ack-after-retry'))
    return 'ACK'
  }
  await h.flushOutbox()
  const sends = h.requests.filter(value => value.event === 'CHAT_SEND')
  assert.equal(sends.length, 2)
  assert.deepEqual(sends[0], sends[1])
  assert.equal(sends[1].metadata.clientMsgId, 'client-msg-0001')
  assert.equal(h.outbox.entries.value.length, 0)
})

test('three ACK timeouts preserve the failed entry for manual retry without duplicate storage', async () => {
  const h = harness(); await enqueue(h)
  await h.flushOutbox()
  assert.equal(h.requests.filter(value => value.event === 'CHAT_SEND').length, 3)
  assert.equal(h.stored.size, 1)
  assert.equal(h.outbox.entries.value[0].state, 'FAILED')
  assert.equal(h.outbox.entries.value[0].retryCount, 3)
  await h.outbox.retryFailed()
  assert.equal(h.outbox.readyEntries()[0].clientMsgId, 'client-msg-0001')
})
