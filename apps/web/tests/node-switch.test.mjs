import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/platform/nodeSwitch.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { performNodeSwitch } = await import(moduleUrl)

const nodeB = {
  nodeId: 'node-b',
  nodeName: '节点 B',
  appUrl: 'http://192.168.1.20:8080/app/',
  apiOrigin: 'http://192.168.1.20:8080',
  apiBasePath: '/api/v1',
}

function harness({ current, confirmed = true, logoutRejects = false, clearRejects = false } = {}) {
  const order = []
  return {
    order,
    deps: {
      selectedNode: () => current ?? null,
      confirm: async () => {
        order.push('confirm')
        return confirmed
      },
      nativeLogout: async (origin) => {
        order.push(`nativeLogout:${origin}`)
        if (logoutRejects) throw new Error('network unreachable')
      },
      clearNodeSession: async (origin) => {
        order.push(`clearNodeSession:${origin}`)
        if (clearRejects) throw new Error('node origin has not completed the native handshake')
      },
      clearLocalChatDatabase: async () => { order.push('clearLocalChatDatabase') },
      clearSession: () => { order.push('clearSession') },
      clearCacheOwner: () => { order.push('clearCacheOwner') },
      selectNode: (node) => { order.push(`selectNode:${node.nodeId}`) },
      readToken: () => 'token-a',
    },
  }
}

const nodeA = { nodeId: 'node-a', origin: 'http://10.0.0.5:8080', apiBasePath: '/api/v1' }

test('switching from node A to node B logs out and clears A before selecting B', async () => {
  const { deps, order } = harness({ current: nodeA })

  assert.equal(await performNodeSwitch(deps, nodeB), true)
  assert.deepEqual(order, [
    'confirm',
    'nativeLogout:http://10.0.0.5:8080',
    'clearNodeSession:http://10.0.0.5:8080',
    'clearLocalChatDatabase',
    'clearSession',
    'clearCacheOwner',
    'selectNode:node-b',
  ])
})

test('a failed network logout still clears the old node local auth state', async () => {
  const { deps, order } = harness({ current: nodeA, logoutRejects: true })

  assert.equal(await performNodeSwitch(deps, nodeB), true)
  assert.ok(order.includes('clearNodeSession:http://10.0.0.5:8080'))
  assert.ok(order.includes('clearSession'))
  assert.equal(order.at(-1), 'selectNode:node-b')
})

test('cancelling the confirm keeps the current node untouched', async () => {
  const { deps, order } = harness({ current: nodeA, confirmed: false })

  assert.equal(await performNodeSwitch(deps, nodeB), false)
  assert.deepEqual(order, ['confirm'])
})

test('selecting the already-active node is a no-op without confirmation', async () => {
  const { deps, order } = harness({
    current: { nodeId: 'node-b', origin: 'http://192.168.1.20:8080', apiBasePath: '/api/v1' },
  })

  assert.equal(await performNodeSwitch(deps, nodeB), false)
  assert.deepEqual(order, [])
})

test('first-time selection with no current node skips logout entirely', async () => {
  const { deps, order } = harness({ current: null })

  assert.equal(await performNodeSwitch(deps, nodeB), true)
  assert.deepEqual(order, [
    'clearLocalChatDatabase',
    'clearSession',
    'clearCacheOwner',
    'selectNode:node-b',
  ])
})

test('a rejected native cookie clearing still lets the user leave a dead node', async () => {
  const { deps, order } = harness({ current: nodeA, logoutRejects: true, clearRejects: true })

  assert.equal(await performNodeSwitch(deps, nodeB), true)
  assert.equal(order.at(-1), 'selectNode:node-b')
  assert.ok(order.includes('clearSession'))
})
