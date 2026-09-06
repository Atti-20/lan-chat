import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from '../node_modules/typescript/lib/typescript.js'

// Execute the actual production handlers with deterministic storage/network
// boundaries; no copied implementation of their selection/visibility policy.
const raw = await readFile(new URL('../src/composables/useChat.ts', import.meta.url), 'utf8')
const ast = ts.createSourceFile('useChat.ts', raw, ts.ScriptTarget.Latest, true)
const names = new Set(['canReadSelectedConversation', 'belongsToSelected', 'handleDelivery'])
const declarations = []
function collect(node) {
  if (ts.isFunctionDeclaration(node) && names.has(node.name?.text)) declarations.push(node.getText(ast))
  ts.forEachChild(node, collect)
}
collect(ast)
assert.equal(declarations.length, names.size)
const compiled = ts.transpileModule(declarations.join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText

function fixture() {
  const state = { foreground: true, workspace: true }
  const selected = { value: { conversationId: 'group:7' } }
  const loadingMessages = { value: false }
  const events = []
  const storage = { write: async () => undefined }
  const dependencies = {
    selected, loadingMessages,
    options: { isConversationVisible: () => state.workspace },
    isAppForeground: () => state.foreground,
    currentUser: { value: { id: 161 } },
    normalizeMessage: value => ({ ...value }),
    inaccessibleConversationIds: new Set(), recalledMessageIds: new Set(),
    rememberRecalledMessage: () => undefined, registerSystemNotificationAccount: () => undefined,
    conversations: { value: [] }, runtimePositions: new Map(),
    cacheMessages: () => storage.write(), recordPosition: async () => undefined,
    updateConversationPreview: (message, visible) => events.push(['preview', visible]),
    mergeCurrentMessages: messages => events.push(['merge', messages[0].conversationId]),
    startBurnCountdown: () => events.push(['burn']),
    sendReadPosition: () => events.push(['read']),
    toast: { push: () => undefined }, playNotificationSound: () => undefined,
    conversationPreview: () => '', scheduleIncomingNotification: () => events.push(['notify']),
    ws: { sendEvent: () => undefined }, createRequestId: () => 'test',
    refreshAndSynchronize: async () => undefined,
  }
  const handle = new Function(...Object.keys(dependencies), `${compiled}; return handleDelivery;`)(...Object.values(dependencies))
  const message = { payload: { messageId: 'visibility-message', conversationId: 'group:7', fromUserId: 162, sequence: 1, type: 'text', isBurn: 1 } }
  return { state, selected, loadingMessages, events, storage, deliver: () => handle(message) }
}

test('background, loading and modal-covered conversations do not clear unread or start read/burn', async () => {
  for (const condition of ['background', 'loading', 'overlay']) {
    const f = fixture()
    if (condition === 'background') f.state.foreground = false
    if (condition === 'loading') f.loadingMessages.value = true
    if (condition === 'overlay') f.state.workspace = false
    await f.deliver()
    assert.deepEqual(f.events, [['preview', false], ['merge', 'group:7'], ['notify']], condition)
  }
})

test('a selected visible loaded conversation is read and starts its burn countdown', async () => {
  const f = fixture()
  await f.deliver()
  assert.deepEqual(f.events, [['preview', true], ['merge', 'group:7'], ['burn'], ['read'], ['notify']])
})

test('switching conversation during a delayed cache write cannot merge A into B or clear A unread', async () => {
  const f = fixture()
  let release
  f.storage.write = () => new Promise(resolve => { release = resolve })
  const pending = f.deliver()
  f.selected.value = { conversationId: 'group:8' }
  release()
  await pending
  assert.deepEqual(f.events, [['preview', false], ['notify']])
})
