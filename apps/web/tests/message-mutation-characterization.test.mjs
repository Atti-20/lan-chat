import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import ts from 'typescript'
import { createTsLoader, repositoryRoot, plain } from './helpers/load-ts.mjs'

const v = JSON.parse(readFileSync(`${repositoryRoot}/contracts/test-vectors/message-mutation-v1.json`, 'utf8'))
const source = readFileSync(`${repositoryRoot}/apps/web/src/composables/useChat.ts`, 'utf8')
const ast = ts.createSourceFile('useChat.ts', source, ts.ScriptTarget.Latest, true)
// Execute actual composable handlers; replace I/O and presentation boundaries only.
const names = new Set(['handleSocketMessage', 'handleSyncResponse', 'handleMessageMutation', 'rememberRecalledMessage',
  'handleDelivery', 'recordPosition', 'recordSynchronizedPositions', 'mergeCurrentMessages', 'mergeMessages',
  'normalizeMessage', 'belongsToSelected', 'handleConversationRemoved', 'forgetConversation',
  'canReadSelectedConversation', 'transmitReadPosition', 'flushPendingReadPositions'])
const bodies = []
function collect(node) {
  if (ts.isFunctionDeclaration(node) && names.has(node.name?.text)) bodies.push(node.getText(ast))
  ts.forEachChild(node, collect)
}
collect(ast); assert.equal(bodies.length, names.size)
const compiled = ts.transpileModule(bodies.join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

function harness() {
  const cached = new Map(), persisted = new Map(), requests = [], notifications = []
  const messages = vue.ref([]), selected = vue.ref({ conversationId: v.conversationId, kind: 'private' })
  const load = createTsLoader(), rules = load('packages/domain-ts/src/messages.ts')
  let foreground = true, visible = true
  const dependencies = {
    recoveryEnabled: { value: false },
    currentUser: { value: { id: 1 } }, selected, messages, members: vue.ref([]),
    runtimePositions: new Map([[v.conversationId, 62]]), pendingReadPositions: new Map(),
    ordinaryReadProofRevision: { value: 0 },
    inaccessibleConversationIds: new Set(), recalledMessageIds: new Set(), recalledMessageOrder: [],
    conversations: { value: [] }, loadingMessages: { value: false },
    options: { isConversationVisible: () => visible }, isAppForeground: () => foreground,
    normalizeChatMessage: rules.normalizeMessage, mergeChatMessages: rules.mergeMessages, sortMessages: rules.sortMessages,
    advanceContiguousSequence: load('packages/domain-ts/src/sequence.ts').advanceContiguousSequence,
    async cacheMessages(values) { for (const value of values) cached.set(value.messageId, plain(value)) },
    async markCachedMessageRecalled(cid, id) { const old = cached.get(id); if (old?.conversationId === cid) cached.set(id, { ...old, isRecalled: 1, content: '' }) },
    async savePosition(id, sequence) { persisted.set(id, sequence) },
    updateConversationPreview() {}, registerSystemNotificationAccount() {}, getConversationSummary() {}, recordSummaryDelta() {},
    refreshConversationSummaries: async () => {}, refreshAndSynchronize: async () => {},
    startBurnCountdown() {}, scheduleBurnCountdowns() {}, clearBurnCountdown() {},
    toast: { push() {} }, playNotificationSound() {}, conversationPreview: () => '',
    scheduleIncomingNotification: message => notifications.push(message.messageId),
    outbox: { remove: async () => {}, find: () => undefined },
    syncRequestId: null, finishSync() {}, mentionReceiptRefreshRevision: { value: 0 },
    ws: { connected: { value: true }, sendEvent: (event, payload, metadata) => { requests.push({ event, payload, metadata }); return true } },
    createRequestId: () => 'mutation-read-request',
  }
  const handlers = new Function(...Object.keys(dependencies), `${compiled}; return { ${[...names].join(',')} };`)(...Object.values(dependencies))
  return { ...handlers, ...dependencies, cached, persisted, requests, notifications,
    set foreground(value) { foreground = value }, set visible(value) { visible = value } }
}

for (const vector of v.cases.filter(c => c.id !== 'stale-connection-mutation')) {
  test(`v1 Web characterization: ${vector.id}`, async () => {
    const h = harness()
    for (const op of vector.order) {
      if (op === 'original') await h.handleDelivery({ event: 'CHAT_DELIVER', payload: plain(v.original) })
      else if (op === 'reconnect') await h.handleSyncResponse(plain(v.reconnect))
      else if (op === 'removed') h.handleConversationRemoved(plain(v.removed))
      else if (op === 'unknown') await h.handleSocketMessage(plain(v.unknown))
      else await h.handleMessageMutation(plain(v[op]))
    }
    if (['duplicate-mutation', 'mutation-before-original', 'mutation-after-original'].includes(vector.id)) {
      assert.equal(h.cached.get(v.original.messageId)?.content ?? '', '')
      assert.ok(h.messages.value.every(m => m.content === ''))
    } else if (vector.id === 'removed-conversation') {
      assert.equal(h.selected.value, null); assert.equal(h.messages.value.length, 0)
      assert.ok(h.inaccessibleConversationIds.has(v.conversationId))
      assert.equal(h.cached.get(v.original.messageId).content, v.original.content) // Known persistent-cache gap.
    } else {
      assert.equal(h.cached.get(v.original.messageId).content, v.original.content)
      assert.equal(h.messages.value[0].content, v.original.content) // Safety FAIL, characterization PASS.
    }
    assert.deepEqual(h.notifications, []) // Empty mutation correction is not a new delivery.
  })
}

test('Web burn for an unselected cached message is not durably invalidated', async () => {
  const h = harness()
  h.cached.set(v.original.messageId, plain(v.original))
  h.selected.value = null
  await h.handleMessageMutation(plain(v.burn))
  assert.equal(h.cached.get(v.original.messageId).content, v.original.content)
})
