import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/platform/navigationTarget.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const {
  authorizeCapacitorNotificationTarget,
  createNotificationNavigationHandler,
  LOCAL_NOTIFICATION_ACTION_EVENT,
  MonotonicNotificationIdAllocator,
  NavigationDeliveryDeduper,
  PendingNavigationStore,
  normalizeNavigationTarget,
  parseNotificationNavigation,
  pendingTargetBelongsToNode,
} = await import(moduleUrl)
const navigationStateSource = await readFile(
  new URL('../src/components/chat/navigationState.ts', import.meta.url),
  'utf8',
)
const navigationStateCompiled = ts.transpileModule(navigationStateSource, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const navigationStateModule = await import(
  `data:text/javascript;base64,${Buffer.from(navigationStateCompiled).toString('base64')}`
)

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  }
}

test('parses all supported notification targets with a stable delivery id', () => {
  for (const target of [
    { kind: 'conversation', value: 'private:1:2' },
    { kind: 'room', value: 'ROOM_123' },
    { kind: 'broadcast', value: '42' },
    { kind: 'node', value: 'https://mesh.example:8443' },
  ]) {
    const delivery = parseNotificationNavigation({
      actionId: 'tap',
      notification: {
        id: 77,
        extra: { lanchatTarget: JSON.stringify(target) },
      },
    })
    assert.equal(delivery?.deliveryId, '77:tap')
    assert.equal(delivery?.target.kind, target.kind)
  }
})

test('rejects malformed or unsafe metadata', () => {
  assert.equal(parseNotificationNavigation({ notification: { id: 1, extra: {} } }), null)
  assert.equal(parseNotificationNavigation({
    notification: { id: 1, extra: { lanchatTarget: '{bad' } },
  }), null)
  assert.equal(normalizeNavigationTarget({
    kind: 'node',
    value: 'https://user:secret@example.test/path',
  }), null)
  assert.equal(normalizeNavigationTarget({ kind: 'broadcast', value: '-1' }), null)
})

test('Capacitor rejects notification targets before a node is explicitly trusted', () => {
  assert.deepEqual(authorizeCapacitorNotificationTarget({
    kind: 'conversation',
    value: 'private:1:2',
    nodeOrigin: 'https://mesh.example',
  }, null), {
    allowed: false,
    reason: 'NO_SELECTED_NODE',
  })
})

test('Capacitor requires every notification target to carry the selected node origin', () => {
  const current = { nodeId: 'node-a', origin: 'https://mesh.example' }
  assert.deepEqual(authorizeCapacitorNotificationTarget({
    kind: 'room',
    value: 'ROOM_123',
  }, current), {
    allowed: false,
    reason: 'MISSING_NODE_ORIGIN',
  })
  assert.deepEqual(authorizeCapacitorNotificationTarget({
    kind: 'broadcast',
    value: '42',
    nodeOrigin: 'https://forged.example',
  }, current), {
    allowed: false,
    reason: 'NODE_ORIGIN_MISMATCH',
  })
})

test('Capacitor node notifications cannot activate or switch away from the selected node', () => {
  const current = { nodeId: 'node-a', origin: 'https://mesh.example' }
  assert.deepEqual(authorizeCapacitorNotificationTarget({
    kind: 'node',
    value: 'node-b',
    nodeOrigin: 'https://forged.example',
  }, current), {
    allowed: false,
    reason: 'NODE_ORIGIN_MISMATCH',
  })
  assert.deepEqual(authorizeCapacitorNotificationTarget({
    kind: 'node',
    value: 'node-b',
  }, current), {
    allowed: false,
    reason: 'MISSING_NODE_ORIGIN',
  })

  const allowed = authorizeCapacitorNotificationTarget({
    kind: 'node',
    value: 'https://mesh.example',
    nodeOrigin: 'https://mesh.example',
  }, current)
  assert.equal(allowed.allowed, true)
  assert.equal(allowed.allowed && allowed.target.nodeOrigin, current.origin)
})

test('Capacitor accepts in-node content navigation with an exact origin match', () => {
  const target = {
    kind: 'conversation',
    value: 'private:1:2',
    nodeOrigin: 'https://mesh.example',
  }
  assert.deepEqual(authorizeCapacitorNotificationTarget(target, {
    nodeId: 'node-a',
    origin: 'https://mesh.example',
  }), {
    allowed: true,
    target: {
      ...target,
      nodeOrigin: 'https://mesh.example',
    },
  })
})

test('deduplicates a delivery until its expiry window', () => {
  let now = 1_000
  const deduper = new NavigationDeliveryDeduper(() => now, 100, 4)
  assert.equal(deduper.accept('9:tap'), true)
  assert.equal(deduper.accept('9:tap'), false)
  now += 101
  assert.equal(deduper.accept('9:tap'), true)
})

test('allocates distinct Android notification ids within the same millisecond', () => {
  const allocator = new MonotonicNotificationIdAllocator(() => 42_000)
  const ids = [allocator.next(), allocator.next(), allocator.next()]

  assert.deepEqual(ids, [42_000, 42_001, 42_002])
  assert.equal(new Set(ids).size, ids.length)
})

test('native listener maps the retained Capacitor event once and ignores malformed actions', () => {
  assert.equal(LOCAL_NOTIFICATION_ACTION_EVENT, 'localNotificationActionPerformed')
  const targets = []
  const handler = createNotificationNavigationHandler(
    (target) => targets.push(target),
    new NavigationDeliveryDeduper(() => 1_000, 100, 4),
  )
  const action = {
    actionId: 'tap',
    notification: {
      id: 88,
      extra: {
        lanchatTarget: JSON.stringify({ kind: 'broadcast', value: '42' }),
      },
    },
  }

  handler(action)
  handler(action)
  handler({ notification: { id: 89, extra: { lanchatTarget: '{bad' } } })

  assert.deepEqual(targets, [{
    kind: 'broadcast',
    value: '42',
    nodeOrigin: null,
  }])
})

test('stores pending navigation, atomically claims it, and expires stale entries', () => {
  let now = 5_000
  const store = new PendingNavigationStore(memoryStorage(), 'pending', () => now, 100)
  const conversation = { kind: 'conversation', value: 'private:1:2' }
  const normalizedConversation = { ...conversation, nodeOrigin: null }
  assert.deepEqual(store.store(conversation), normalizedConversation)
  assert.equal(store.claim({ kind: 'broadcast', value: '2' }), null)
  assert.deepEqual(store.claim(conversation), normalizedConversation)
  assert.equal(store.claim(conversation), null)

  store.store({ kind: 'room', value: 'ROOM_123' })
  now += 101
  assert.equal(store.pending(), null)
})

test('Android back follows modal, workspace, conversation, then exit priority', () => {
  const empty = Object.fromEntries([
    'emergencyAlert',
    'passwordReset',
    'profileEditor',
    'contextPanel',
    'searchPeople',
    'createGroup',
    'createRoom',
    'joinRoom',
    'createBroadcast',
    'devices',
    'password',
    'fileTransferSettings',
    'desktopSettings',
    'profile',
    'adminModule',
    'broadcast',
    'conversation',
  ].map((key) => [key, false]))

  assert.equal(navigationStateModule.nextBackAction({
    ...empty,
    conversation: true,
  }), 'conversation')
  assert.equal(navigationStateModule.nextBackAction({
    ...empty,
    adminModule: true,
    conversation: true,
  }), 'admin-module')
  assert.equal(navigationStateModule.nextBackAction({
    ...empty,
    emergencyAlert: true,
    adminModule: true,
    conversation: true,
  }), 'emergency-alert')
  assert.equal(navigationStateModule.nextBackAction(empty), 'exit')
})

test('a pending target from another node is refused at consume time', () => {
  const target = {
    kind: 'conversation',
    value: 'private:7:9',
    nodeOrigin: 'http://10.0.0.5:8080',
  }

  assert.equal(
    pendingTargetBelongsToNode(target, { nodeId: 'node-b', origin: 'http://192.168.1.20:8080' }),
    false,
  )
  assert.equal(pendingTargetBelongsToNode(target, null), false)
  assert.equal(
    pendingTargetBelongsToNode(target, { nodeId: 'node-a', origin: 'http://10.0.0.5:8080' }),
    true,
  )
})

test('a pending target without a node origin is not blocked on consume', () => {
  assert.equal(
    pendingTargetBelongsToNode(
      { kind: 'conversation', value: 'private:7:9' },
      { nodeId: 'node-b', origin: 'http://192.168.1.20:8080' },
    ),
    true,
  )
})
