import { expect, test } from '@playwright/test'
import { ApiClient, createUser } from '../src/api.js'
import { WsClient } from '../src/ws-client.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'
const instanceB = process.env.E2E_INSTANCE_B_URL || 'http://127.0.0.1:18082'

test('same-type login sends FORCE_LOGOUT and closes the replaced WebSocket', async () => {
  const firstApi = new ApiClient(instanceA)
  const user = await createUser(firstApi, 'e2e_force')
  const firstWs = new WsClient(instanceA, user.session.token)
  let replacementWs: WsClient | undefined

  try {
    await firstWs.connect()
    const cursor = firstWs.messageCount
    const forceLogout = firstWs.waitFor(
      (event) => event.event === 'FORCE_LOGOUT',
      20_000,
      cursor,
    )
    const closed = firstWs.waitForClose(20_000)

    const replacementApi = new ApiClient(instanceA)
    const replacement = await replacementApi.login(
      user.username,
      user.password,
      'LANChat E2E replacement browser',
      'web',
    )

    const [forced, close] = await Promise.all([forceLogout, closed])
    expect(forced.payload.message).toBeTruthy()
    expect(close.code).toBe(1008)

    replacementWs = new WsClient(instanceA, replacement.token)
    await replacementWs.connect()
    expect(replacementWs.isOpen).toBe(true)
  } finally {
    firstWs.close()
    replacementWs?.close()
  }
})

test('failed replacement insert rolls back and leaves the old session usable', async () => {
  const originalApi = new ApiClient(instanceA)
  const user = await createUser(originalApi, 'e2e_login_rollback')
  const originalWs = new WsClient(instanceA, user.session.token)

  try {
    await originalWs.connect()
    const replacementApi = new ApiClient(instanceA)
    await expect(replacementApi.login(
      user.username,
      user.password,
      '__E2E_FAIL_DEVICE_LOGIN_INSERT__',
      'web',
    )).rejects.toThrow(/500|操作失败/)

    await expect(originalApi.me(user.session.token)).resolves.toMatchObject({
      id: user.session.userId,
    })
    expect(originalWs.isOpen).toBe(true)
    const cursor = originalWs.messageCount
    const requestId = originalWs.id('rollback_ping')
    const pong = originalWs.waitFor(
      (event) => event.event === 'PONG' && event.requestId === requestId,
      10_000,
      cursor,
    )
    originalWs.send('PING', {}, { requestId })
    await expect(pong).resolves.toBeTruthy()
  } finally {
    originalWs.close()
  }
})

test('concurrent same-type logins leave one active row and close the exact old socket', async () => {
  const originalApi = new ApiClient(instanceA)
  const user = await createUser(originalApi, 'e2e_login_race')
  const originalWs = new WsClient(instanceA, user.session.token)
  let winningWs: WsClient | undefined

  try {
    await originalWs.connect()
    const cursor = originalWs.messageCount
    const forced = originalWs.waitFor(
      (event) => event.event === 'FORCE_LOGOUT',
      20_000,
      cursor,
    )
    const closed = originalWs.waitForClose(20_000)

    const firstApi = new ApiClient(instanceA)
    const secondApi = new ApiClient(instanceA)
    const [first, second] = await Promise.all([
      firstApi.login(user.username, user.password, 'E2E same-user race A', 'web'),
      secondApi.login(user.username, user.password, 'E2E same-user race B', 'web'),
    ])

    const probes = await Promise.allSettled([
      firstApi.me(first.token),
      secondApi.me(second.token),
    ])
    expect(probes.filter((result) => result.status === 'fulfilled')).toHaveLength(1)
    expect(probes.filter((result) => result.status === 'rejected')).toHaveLength(1)

    const [forceEvent, close] = await Promise.all([forced, closed])
    expect(forceEvent.payload.reason).toBe('SESSION_REPLACED')
    expect(close.code).toBe(1008)

    const winner = probes[0]?.status === 'fulfilled' ? first : second
    winningWs = new WsClient(instanceA, winner.token)
    await winningWs.connect()
    expect(winningWs.isOpen).toBe(true)
  } finally {
    originalWs.close()
    winningWs?.close()
  }
})

test('cold-start navigation waits for login and reports an unavailable target', async ({ page }) => {
  const api = new ApiClient(instanceA)
  const user = await createUser(api, 'e2e_pending_navigation')
  const target = {
    kind: 'conversation',
    value: `private:${user.session.userId}:999999999`,
    nodeOrigin: null,
  }

  await page.addInitScript((pendingTarget) => {
    if (localStorage.getItem('e2e_pending_navigation_seeded')) return
    localStorage.setItem('e2e_pending_navigation_seeded', 'true')
    sessionStorage.setItem('lanchat_native_navigation_v2', JSON.stringify({
      storedAt: Date.now(),
      target: pendingTarget,
    }))
  }, target)

  await page.goto('/app/')
  await page.getByLabel('用户名').fill(user.username)
  await page.getByLabel('密码').fill(user.password)
  await page.getByRole('button', { name: '登录 MeshX' }).click()

  await expect(page.locator('.chat-shell')).toBeVisible()
  await expect(page.locator('.toast')).toContainText('深链指定的会话当前不可用')
  await expect.poll(() => page.evaluate(() =>
    sessionStorage.getItem('lanchat_native_navigation_v2'))).toBeNull()
})

test('native node switch clears the previous node IndexedDB cache owner', async ({ page }) => {
  const api = new ApiClient(instanceA)
  const user = await createUser(api, 'e2e_node_cache')
  const markerId = `stale_${Date.now().toString(36)}`
  const nodeA = {
    nodeId: 'e2e-node-a',
    nodeName: 'E2E node A',
    origin: instanceA,
    apiBasePath: '/api/v1',
    webSocketPath: '/ws/chat',
    healthPath: '/api/v1/node/health',
    appPath: '/app/',
    secure: false,
  }
  const nodeB = { ...nodeA, nodeId: 'e2e-node-b', nodeName: 'E2E node B', origin: instanceB }

  await page.addInitScript(({ session, initialNode }) => {
    // Capacitor's supported custom-platform hook keeps its global writable and
    // lets native-only routing run while JS-backed plugins remain available.
    Object.defineProperty(window, 'CapacitorCustomPlatform', {
      configurable: true,
      value: { name: 'android' },
    })
    sessionStorage.setItem('lanchat_session_v2', JSON.stringify(session))
    if (!localStorage.getItem('e2e_native_node_initialized')) {
      localStorage.setItem('lanchat_native_node_v1', JSON.stringify(initialNode))
      localStorage.setItem('e2e_native_node_initialized', 'true')
    }
  }, { session: user.session, initialNode: nodeA })

  await page.goto('/app/')
  await expect(page.locator('.chat-shell')).toBeVisible()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('lanchat_cache_owner')))
    .toBe(`${nodeA.nodeId}@${new URL(instanceA).origin}::${user.session.userId}`)

  await page.evaluate(async (clientMsgId) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('lanchat_local_v2')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction('outbox', 'readwrite')
      transaction.objectStore('outbox').put({
        clientMsgId,
        state: 'FAILED',
        createdAt: new Date().toISOString(),
      })
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error)
    })
    database.close()
  }, markerId)

  await page.evaluate((nextNode) => {
    localStorage.setItem('lanchat_native_node_v1', JSON.stringify(nextNode))
  }, nodeB)
  await page.reload()
  await expect(page.locator('.chat-shell')).toBeVisible()

  await expect.poll(() => page.evaluate(async (clientMsgId) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('lanchat_local_v2')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const result = await new Promise<unknown>((resolve, reject) => {
      const transaction = database.transaction('outbox', 'readonly')
      const request = transaction.objectStore('outbox').get(clientMsgId)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    database.close()
    return result == null
  }, markerId)).toBe(true)

  await expect.poll(() => page.evaluate(() => localStorage.getItem('lanchat_cache_owner')))
    .toBe(`${nodeB.nodeId}@${new URL(instanceB).origin}::${user.session.userId}`)
})
