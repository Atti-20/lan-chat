import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  // Keep a real, fresh browser origin without starting the product UI. Import
  // actual Vite modules below; each Playwright context owns its own database.
  await page.route('**/app/__storage_contract__', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Storage contract</title>' }))
  await page.goto('/app/__storage_contract__')
})

test('actual IndexedDB adapter survives reload, replaces duplicate IDs and restores pending outbox', async ({ page }) => {
  const before = await page.evaluate(async () => {
    const path = '/app/src/platform/web/outboxStore.ts'
    const { browserOutboxStore: store } = await import(path)
    const value = { clientMsgId: 'storage-client-01', requestId: 'storage-request-01', conversationId: 'private:1:2',
      payload: { contentType: 'text', content: 'before restart' }, state: 'SENDING', retryCount: 2, createdAt: '2026-09-08T10:00:00Z' }
    await store.save(value)
    await store.save({ ...value, payload: { ...value.payload, content: 'canonical content' } })
    return await store.load()
  })
  expect(before).toHaveLength(1)
  await page.reload()
  const restored = await page.evaluate(async () => {
    const path = '/app/src/composables/useOutbox.ts'
    const { useOutbox } = await import(path)
    const queue = useOutbox()
    await queue.hydrate()
    const storePath = '/app/src/platform/web/outboxStore.ts'
    const { browserOutboxStore: store } = await import(storePath)
    return { ready: queue.readyEntries(), persisted: await store.load(), durable: queue.durable.value }
  })
  expect(restored.durable).toBe(true)
  expect(restored.ready).toHaveLength(1)
  expect(restored.ready[0]).toMatchObject({ clientMsgId: 'storage-client-01', requestId: 'storage-request-01', retryCount: 2,
    state: 'WAITING_NETWORK', payload: { content: 'canonical content' } })
  expect(restored.persisted).toEqual(restored.ready)
})

test('duplicate delivery and ACK cleanup retain one cached server message and monotonic cursor', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const path = '/app/src/services/localChatDb.ts'
    const db = await import(path)
    const conversationId = 'private:1:2', clientMsgId = 'storage-client-02'
    const optimistic = { messageId: 'local:storage-client-02', clientMsgId, conversationId, fromUserId: 1,
      type: 'text', content: 'message', clientCreatedAt: '2026-09-08T10:00:00Z' }
    await db.cacheMessages([optimistic])
    await db.deleteCachedMessagesByClientMsgId(clientMsgId)
    const server = { ...optimistic, messageId: 'storage-server-02', sequence: 7 }
    await db.cacheMessages([server]); await db.cacheMessages([server])
    await db.savePosition(conversationId, 7); await db.savePosition(conversationId, 6)
    return { messages: await db.loadCachedMessages(conversationId), positions: await db.loadPositions() }
  })
  expect(result.messages).toHaveLength(1)
  expect(result.messages[0].messageId).toBe('storage-server-02')
  expect(result.positions['private:1:2']).toBe(7)
})

test('same-account login retains offline data, account switch and explicit logout clear the old owner', async ({ page }) => {
  let userId = 1
  await page.route('**/api/v1/auth/**', route => route.fulfill({ json: { code: 200, msg: 'ok', data: {
    userId, username: `user${userId}`, nickname: `User ${userId}`, token: `contract-access-${userId}`, expiresIn: 3600,
  } } }))
  const initialize = await page.evaluate(async () => {
    const storagePath = '/app/src/utils/storage.ts', dbPath = '/app/src/services/localChatDb.ts', authPath = '/app/src/composables/useAuth.ts'
    const storage = await import(storagePath), db = await import(dbPath), { useAuth } = await import(authPath)
    storage.writeCacheOwner(1)
    await db.saveOutboxEntry({ clientMsgId: 'owner-one-message', requestId: 'owner-one-request', conversationId: 'private:1:2',
      payload: { contentType: 'text', content: 'owner one' }, createdAt: '2026-09-08T10:00:00Z', retryCount: 0, state: 'WAITING_NETWORK' })
    await useAuth().login('user1', 'test-password')
    return { rows: await db.loadOutbox(), owner: storage.readCacheOwner(), expected: storage.cacheOwnerKey(1) }
  })
  expect(initialize.rows).toHaveLength(1)
  expect(initialize.owner).toBe(initialize.expected)
  userId = 2
  const switched = await page.evaluate(async () => {
    const storagePath = '/app/src/utils/storage.ts', dbPath = '/app/src/services/localChatDb.ts', authPath = '/app/src/composables/useAuth.ts'
    const storage = await import(storagePath), db = await import(dbPath), { useAuth } = await import(authPath)
    await useAuth().login('user2', 'test-password')
    return { rows: await db.loadOutbox(), owner: storage.readCacheOwner(), expected: storage.cacheOwnerKey(2), userId: storage.readSession()?.userId }
  })
  expect(switched.rows).toEqual([])
  expect(switched.owner).toBe(switched.expected)
  expect(switched.userId).toBe(2)
  const loggedOut = await page.evaluate(async () => {
    const storagePath = '/app/src/utils/storage.ts', dbPath = '/app/src/services/localChatDb.ts', authPath = '/app/src/composables/useAuth.ts'
    const storage = await import(storagePath), db = await import(dbPath), { useAuth } = await import(authPath)
    await useAuth().logout()
    return { rows: await db.loadOutbox(), owner: storage.readCacheOwner(), session: storage.readSession() }
  })
  expect(loggedOut).toEqual({ rows: [], owner: null, session: null })
})

test('native-node switch clears A records and credentials before selecting B while preserving node cache-key format', async ({ page }) => {
  const result = await page.evaluate(async () => {
    // Only the host capability signal is simulated; node selection, browser
    // storage, IndexedDB and the switch orchestrator are the production modules.
    Object.assign(window, { __TAURI_INTERNALS__: {} })
    const nodePath = '/app/src/platform/nodeContext.ts', switchPath = '/app/src/platform/nodeSwitch.ts'
    const storagePath = '/app/src/utils/storage.ts', dbPath = '/app/src/services/localChatDb.ts'
    const node = await import(nodePath), { performNodeSwitch } = await import(switchPath)
    const storage = await import(storagePath), db = await import(dbPath)
    node.selectNode({ nodeId: 'node-a', nodeName: 'A', apiOrigin: 'http://a.test', appUrl: 'http://a.test/app/', apiBasePath: '/api/v1' })
    storage.writeSession({ userId: 1, token: 'node-a-contract-access' })
    storage.writeCacheOwner(1)
    await db.saveOutboxEntry({ clientMsgId: 'node-a-message', requestId: 'node-a-request', conversationId: 'private:1:2',
      payload: { contentType: 'text', content: 'node A' }, createdAt: '2026-09-08T10:00:00Z', retryCount: 0, state: 'WAITING_NETWORK' })
    const previousOwner = storage.readCacheOwner(), calls: unknown[] = []
    await performNodeSwitch({ selectedNode: node.selectedNode, confirm: async () => true,
      nativeLogout: async (...args: unknown[]) => { calls.push(['logout', ...args]) },
      clearNodeSession: async (origin: string) => { calls.push(['clearNative', origin]) },
      clearLocalChatDatabase: db.clearLocalChatDatabase, clearSession: storage.clearSession,
      clearCacheOwner: storage.clearCacheOwner, selectNode: node.selectNode, readToken: () => storage.readSession()?.token,
    }, { nodeId: 'node-b', nodeName: 'B', apiOrigin: 'https://b.test', appUrl: 'https://b.test/app/', apiBasePath: '/nested/api' })
    return { previousOwner, nextKey: storage.cacheOwnerKey(1), calls, rows: await db.loadOutbox(), session: storage.readSession(), owner: storage.readCacheOwner() }
  })
  expect(result.previousOwner).toBe('node-a@http://a.test::1')
  expect(result.nextKey).toBe('node-b@https://b.test::1')
  expect(result.calls).toEqual([['logout', 'http://a.test', '/api/v1', 'node-a-contract-access'], ['clearNative', 'http://a.test']])
  expect(result.rows).toEqual([])
  expect(result.session).toBeNull(); expect(result.owner).toBeNull()
})
