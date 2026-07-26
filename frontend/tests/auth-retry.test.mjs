import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/services/authRetry.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { resolveUnauthorized } = await import(moduleUrl)

function harness({ refreshResult = true, nodeKeys = ['node-a'] } = {}) {
  const calls = { refresh: [], clearSession: 0 }
  let nodeIndex = 0
  return {
    calls,
    deps: {
      currentNodeKey: () => nodeKeys[Math.min(nodeIndex++, nodeKeys.length - 1)],
      refreshAccessToken: async (expectedNodeKey) => {
        calls.refresh.push(expectedNodeKey)
        return refreshResult
      },
      clearSession: () => { calls.clearSession += 1 },
    },
  }
}

test('an expired access token refreshes once and the request is retried', async () => {
  const { deps, calls } = harness({ refreshResult: true })

  const outcome = await resolveUnauthorized(deps, 'node-a')

  assert.equal(outcome, 'retry')
  assert.deepEqual(calls.refresh, ['node-a'])
  assert.equal(calls.clearSession, 0)
})

test('a failed refresh expires the session on the same node', async () => {
  const { deps, calls } = harness({ refreshResult: false })

  const outcome = await resolveUnauthorized(deps, 'node-a')

  assert.equal(outcome, 'expired')
  assert.deepEqual(calls.refresh, ['node-a'])
  assert.equal(calls.clearSession, 1)
})

test('a node switch during refresh never retries against the new node', async () => {
  const { deps, calls } = harness({ refreshResult: true, nodeKeys: ['node-b'] })

  const outcome = await resolveUnauthorized(deps, 'node-a')

  assert.equal(outcome, 'expired')
  assert.equal(calls.clearSession, 0, 'the new node session must survive')
})

test('a failed refresh after a node switch leaves the new node session intact', async () => {
  const { deps, calls } = harness({ refreshResult: false, nodeKeys: ['node-b'] })

  const outcome = await resolveUnauthorized(deps, 'node-a')

  assert.equal(outcome, 'expired')
  assert.equal(calls.clearSession, 0)
})
