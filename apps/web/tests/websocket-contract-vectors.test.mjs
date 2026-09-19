import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'
import ts from 'typescript'
import { computed, readonly, shallowRef } from 'vue'

const root = new URL('../../../', import.meta.url)
const vectors = JSON.parse(readFileSync(new URL('contracts/fixtures/core-v1.json', root)))
const frame = id => structuredClone(vectors.frames.find(v => v.id === id).frame)
const mutationVectors = JSON.parse(readFileSync(new URL('contracts/test-vectors/message-mutation-v1.json', root)))
function load(path, imports = {}, globals = {}) {
  const source = readFileSync(new URL(path, root), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText
  const exports = {}
  runInNewContext(js, { exports, require: id => {
    assert.ok(id in imports, `Unexpected runtime import ${id}`)
    return imports[id]
  }, ...globals }, { filename: path })
  return exports
}
const protocol = { ...load('packages/protocol/src/envelope.ts'), ...load('packages/protocol/src/events.ts') }
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(setImmediate) }

function harness(options) {
  const sockets = [], timers = new Map()
  let cleanup, nextTimer = 0
  const { useWebSocket } = load('apps/web/src/composables/useWebSocket.ts', {
    vue: { computed, readonly, shallowRef, onBeforeUnmount: callback => { cleanup = callback } },
    '../../../../packages/protocol/src/index': protocol,
    '../../../../packages/domain-ts/src/realtime': load('packages/domain-ts/src/realtime.ts'),
    '../utils/id': { createClientMessageId: () => 'fixture-id' },
    '../utils/storage': { readSession: () => ({ token: 'fixture-access' }) },
    '../platform/nativeTransport': {
      SOCKET_OPEN: 1, SOCKET_CONNECTING: 0,
      realtimeTransport: { open: async () => {
        const socket = { readyState: 1, sent: [],
          send(value) { this.sent.push(JSON.parse(value)) },
          close() { this.readyState = 3; this.onclose?.() },
          receive(value) { this.onmessage?.({ data: JSON.stringify(value) }) },
        }
        sockets.push(socket); return socket
      } },
    },
  }, {
    navigator: { onLine: true }, WebSocket: { OPEN: 1 },
    window: { addEventListener() {}, removeEventListener() {},
      setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer },
      clearTimeout(id) { timers.delete(id) }, setInterval() { return ++nextTimer }, clearInterval() {},
    },
    document: { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} },
  })
  const api = useWebSocket(options)
  return { api, sockets, timers, cleanup: () => cleanup(),
    async open() { api.connect(); await flush(); sockets.at(-1).onopen(); return sockets.at(-1) },
  }
}

test('actual composable preserves AUTH -> SYNCING -> ONLINE, queue order and reconnect sync', async () => {
  let completeSync, unblockMessage
  const received = [], errors = []
  const h = harness({
    onReady: () => new Promise(resolve => {
      completeSync = resolve
      assert.equal(h.api.sendEvent('SYNC_REQUEST', frame('reconnect-sync-request').payload), true)
    }),
    onMessage: async incoming => {
      if (incoming.event === 'SYNC_RESPONSE') completeSync()
      if (incoming.requestId === 'slow') await new Promise(resolve => { unblockMessage = resolve })
      received.push(incoming.requestId ?? incoming.event)
    }, onError: error => errors.push(error),
  })
  try {
    let socket = await h.open()
    assert.equal(socket.sent[0].event, 'AUTH')
    assert.equal(socket.sent[0].payload.token, 'fixture-access')
    socket.receive(frame('auth-ok')); await flush()
    assert.equal(h.api.state.value, 'SYNCING')
    assert.equal(h.api.connected.value, false)
    socket.receive(frame('reconnect-sync-response')); await flush()
    assert.equal(h.api.state.value, 'ONLINE')
    assert.equal(h.api.connected.value, true)
    socket.receive({ ...frame('event-after-ack'), requestId: 'slow' })
    socket.receive({ ...frame('event-after-ack'), requestId: 'later' })
    await flush(); assert.equal(received.includes('later'), false)
    unblockMessage(); await flush()
    assert.deepEqual(received.slice(-2), ['slow', 'later'])
    const count = received.length
    socket.receive({ ...frame('unknown-server'), version: 2 }); await flush()
    assert.equal(received.length, count)
    assert.equal(errors.length, 1)
    socket.receive(frame('unknown-server')); await flush()
    assert.equal(received.at(-1), 'FUTURE_EVENT') // delegated to business handler's ignore/default branch
    socket.close(); assert.equal(h.api.state.value, 'RECONNECTING')
    assert.equal(h.timers.size, 1)
    h.timers.values().next().value(); await flush()
    socket = h.sockets.at(-1); socket.onopen(); socket.receive(frame('auth-ok')); await flush()
    assert.equal(h.api.state.value, 'SYNCING')
    socket.receive(frame('reconnect-sync-response')); await flush()
    assert.equal(h.api.state.value, 'ONLINE')
  } finally { h.cleanup() }
})

test('actual composable refreshes once on expired token and disconnects on force logout', async () => {
  let refreshes = 0
  const failures = []
  const h = harness({ onMessage() {}, refreshAuth: async () => { refreshes++; return true },
    onAuthFailed: reason => failures.push(reason) })
  try {
    const old = await h.open()
    old.receive(frame('token-expired')); old.receive(frame('token-expired')); await flush()
    assert.equal(refreshes, 1)
    assert.equal(h.sockets.length, 2)
    const current = h.sockets.at(-1)
    current.onopen(); current.receive(frame('auth-ok')); await flush()
    assert.equal(h.api.state.value, 'ONLINE')
    current.receive(frame('force-logout')); await flush()
    assert.equal(h.api.state.value, 'OFFLINE')
    assert.deepEqual(failures, ['FORCE_LOGOUT'])
    assert.equal(h.timers.size, 0)
  } finally { h.cleanup() }
})

test('old socket events and delayed synchronization cannot mark a replacement connection online', async () => {
  let finishOldSync
  const messages = []
  const h = harness({ onMessage: envelope => messages.push(envelope.event),
    onReady: () => new Promise(resolve => { finishOldSync = resolve }) })
  try {
    const old = await h.open()
    old.receive(frame('auth-ok')); await flush()
    const lateMessage = old.onmessage, lateClose = old.onclose, lateOpen = old.onopen
    h.api.reconnect(); await flush()
    const replacement = h.sockets.at(-1)
    replacement.onopen()
    lateOpen(); lateClose(); lateMessage({ data: JSON.stringify(frame('force-logout')) })
    finishOldSync(); await flush()
    assert.equal(h.api.state.value, 'AUTHENTICATING')
    assert.equal(replacement.readyState, 1)
    assert.equal(messages.length, 0)
    assert.equal(h.timers.size, 0)
  } finally { h.cleanup() }
})

test('shared stale-connection-mutation vector rejects old RECALL READ REMOVED and supported delivery', async () => {
  assert.ok(mutationVectors.cases.some(c => c.id === 'stale-connection-mutation'))
  const received = []
  const h = harness({ onMessage: message => received.push(message.event) })
  try {
    const old = await h.open()
    old.receive(frame('auth-ok')); await flush()
    const late = old.onmessage
    h.api.reconnect(); await flush()
    const current = h.sockets.at(-1); current.onopen(); current.receive(frame('auth-ok')); await flush()
    for (const message of [mutationVectors.recall, mutationVectors.removed,
      { ...mutationVectors.recall, event: 'CHAT_READ', payload: { userId: 1, lastSequence: 62, lastReadSequence: 62, unreadCount: 0 } },
      frame('event-after-ack')]) late({ data: JSON.stringify(message) })
    await flush(); assert.deepEqual(received, [])
    current.receive(mutationVectors.recall); await flush()
    assert.deepEqual(received, ['CHAT_RECALL'])
  } finally { h.cleanup() }
})

test('logout while token refresh is pending cannot reopen or report failure into the next session', async () => {
  for (const result of [true, false]) {
    let finishRefresh
    const failures = []
    const h = harness({ onMessage() {}, refreshAuth: () => new Promise(resolve => { finishRefresh = resolve }),
      onAuthFailed: reason => failures.push(reason) })
    try {
      const old = await h.open()
      old.receive(frame('token-expired')); await flush()
      h.api.disconnect()
      finishRefresh(result); await flush()
      assert.equal(h.sockets.length, 1)
      assert.equal(h.api.state.value, 'OFFLINE')
      assert.deepEqual(failures, [])
    } finally { h.cleanup() }
  }
})

test('a new connection does not wait for an old token refresh before processing its own AUTH_OK', async () => {
  let finishRefresh
  const h = harness({ onMessage() {}, refreshAuth: () => new Promise(resolve => { finishRefresh = resolve }) })
  try {
    const old = await h.open()
    old.receive(frame('token-expired')); await flush()
    h.api.reconnect(); await flush()
    const replacement = h.sockets.at(-1)
    replacement.onopen(); replacement.receive(frame('auth-ok')); await flush()
    assert.equal(h.api.state.value, 'ONLINE')
    finishRefresh(true); await flush()
    assert.equal(h.sockets.length, 2)
    assert.equal(h.api.state.value, 'ONLINE')
  } finally { finishRefresh?.(false); h.cleanup() }
})

test('a replacement session can refresh independently and an old result cannot clear the new refresh', async () => {
  const refreshes = []
  const failures = []
  const h = harness({ onMessage() {}, refreshAuth: () => new Promise(resolve => { refreshes.push(resolve) }),
    onAuthFailed: value => failures.push(value) })
  try {
    const old = await h.open()
    old.receive(frame('token-expired')); await flush()
    h.api.reconnect(); await flush()
    const replacement = h.sockets.at(-1)
    replacement.onopen(); replacement.receive(frame('token-expired')); await flush()
    assert.equal(refreshes.length, 2)
    refreshes[0](false); await flush()
    assert.deepEqual(failures, [])
    refreshes[1](true); await flush()
    assert.equal(h.sockets.length, 3)
    h.sockets.at(-1).onopen(); h.sockets.at(-1).receive(frame('auth-ok')); await flush()
    assert.equal(h.api.state.value, 'ONLINE')
  } finally { refreshes.forEach(resolve => resolve(false)); h.cleanup() }
})

test('reconnecting preserves serialization of an already-running business storage handler', async () => {
  let finishOldMessage
  const order = []
  const h = harness({ onMessage: async incoming => {
    if (incoming.requestId === 'old-storage') await new Promise(resolve => { finishOldMessage = resolve })
    order.push(incoming.requestId)
  } })
  try {
    const old = await h.open()
    old.receive({ ...frame('event-after-ack'), requestId: 'old-storage' }); await flush()
    h.api.reconnect(); await flush()
    const current = h.sockets.at(-1)
    current.onopen(); current.receive(frame('auth-ok'))
    current.receive({ ...frame('event-after-ack'), requestId: 'new-storage' }); await flush()
    assert.deepEqual(order, [])
    finishOldMessage(); await flush()
    assert.deepEqual(order, ['old-storage', 'new-storage'])
    assert.equal(h.api.state.value, 'ONLINE')
  } finally { finishOldMessage?.(); h.cleanup() }
})
