import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from '../node_modules/typescript/lib/typescript.js'
import { createTsLoader } from './helpers/load-ts.mjs'

const raw = await readFile(new URL('../src/composables/useChat.ts', import.meta.url), 'utf8')
const ast = ts.createSourceFile('useChat.ts', raw, ts.ScriptTarget.Latest, true)
const names = new Set(['observeOrdinaryRead', 'transmitReadPosition', 'flushPendingReadPositions', 'handleReadEvent'])
const declarations = []
function collect(node) {
  if (ts.isFunctionDeclaration(node) && names.has(node.name?.text)) declarations.push(node.getText(ast))
  ts.forEachChild(node, collect)
}
collect(ast)
assert.equal(declarations.length, names.size)
const compiled = ts.transpileModule(declarations.join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText

test('ordinary Web reading waits for dwell and server CHAT_READ before changing unread state', () => {
  const cid = 'private:1:2'
  const summary = { conversationId: cid, lastSequence: 1, lastReadSequence: 0, unreadCount: 1 }
  const selected = { value: { conversationId: cid, unreadCount: 1 } }
  const messages = { value: [{ messageId: 'm1', conversationId: cid, sequence: 1, fromUserId: 2 }] }
  const pendingReadPositions = new Map()
  const sent = []
  let now = 0
  const dependencies = {
    selected,
    messages,
    getConversationSummary: () => summary,
    loadingMessages: { value: false },
    ws: {
      connected: { value: true },
      sendEvent: (event, payload, metadata) => {
        sent.push({ event, payload, metadata })
        return true
      },
    },
    isAppForeground: () => true,
    options: { isConversationVisible: () => true },
    ordinaryReadScope: { value: 'ordinary-proof' },
    ordinaryReadTracker: null,
    VisibleReadTracker: createTsLoader()('packages/domain-ts/src/visibleRead.ts').VisibleReadTracker,
    runtimePositions: new Map([[cid, 1]]),
    currentUser: { value: { id: 1 } },
    pendingReadPositions,
    inaccessibleConversationIds: new Set(),
    recoveryEnabled: { value: false },
    createRequestId: () => 'read-proof-request',
    conversationReadRequiresSnapshot: () => false,
    conversationSummaries: { value: {} },
    recordSummaryDelta: ({ value }) => {
      summary.lastSequence = value.lastSequence
      summary.lastReadSequence = value.lastReadSequence
      summary.unreadCount = value.unreadCount
    },
    refreshConversationSummariesAfterCurrent: async () => undefined,
    performance: { now: () => now },
  }
  const handlers = new Function(
    ...Object.keys(dependencies),
    `${compiled}; return { ${[...names].join(',')} };`,
  )(...Object.values(dependencies))

  handlers.observeOrdinaryRead('ordinary-proof', [1])
  assert.deepEqual(sent, [])
  for (now = 100; now <= 300; now += 100) {
    handlers.observeOrdinaryRead('ordinary-proof', [1])
  }
  assert.deepEqual(sent, [{
    event: 'CHAT_READ',
    payload: { lastReadSequence: 1 },
    metadata: { requestId: 'read-proof-request', conversationId: cid },
  }])
  assert.equal(summary.unreadCount, 1)
  assert.equal(pendingReadPositions.get(cid), 1)

  handlers.handleReadEvent({
    conversationId: cid,
    payload: { userId: 1, lastSequence: 1, lastReadSequence: 1, unreadCount: 0 },
  })
  assert.equal(summary.unreadCount, 0)
  assert.equal(selected.value.unreadCount, 0)
  assert.equal(pendingReadPositions.has(cid), false)
})
