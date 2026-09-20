import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/services/nodeRefreshCoordinator.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { NodeRefreshCoordinator } = await import(moduleUrl)

test('coalesces refreshes for the same node', async () => {
  const coordinator = new NodeRefreshCoordinator()
  let calls = 0
  let resolveRefresh
  const refresh = () => {
    calls += 1
    return new Promise((resolve) => { resolveRefresh = resolve })
  }
  const committed = []

  const first = coordinator.run('node-a', refresh, () => true, (value) => committed.push(value))
  const second = coordinator.run('node-a', refresh, () => true, (value) => committed.push(value))
  assert.strictEqual(first, second)
  resolveRefresh({ token: 'a' })

  assert.equal(await first, true)
  assert.equal(calls, 1)
  assert.deepEqual(committed, [{ token: 'a' }])
})

test('does not coalesce different nodes', async () => {
  const coordinator = new NodeRefreshCoordinator()
  const committed = []
  const [a, b] = await Promise.all([
    coordinator.run('node-a', async () => ({ token: 'a' }), () => true, (value) => committed.push(value)),
    coordinator.run('node-b', async () => ({ token: 'b' }), () => true, (value) => committed.push(value)),
  ])

  assert.deepEqual([a, b], [true, true])
  assert.deepEqual(committed.map(({ token }) => token).sort(), ['a', 'b'])
})

test('rejects a stale refresh result after node switch', async () => {
  const coordinator = new NodeRefreshCoordinator()
  let current = 'node-a'
  let resolveRefresh
  const committed = []
  const result = coordinator.run(
    'node-a',
    () => new Promise((resolve) => { resolveRefresh = resolve }),
    (nodeKey) => current === nodeKey,
    (value) => committed.push(value),
  )
  current = 'node-b'
  resolveRefresh({ token: 'stale' })

  assert.equal(await result, false)
  assert.deepEqual(committed, [])
})

test('clears the single-flight entry after a failed refresh', async () => {
  const coordinator = new NodeRefreshCoordinator()
  const failed = await coordinator.run('node-a', async () => {
    throw new Error('offline')
  }, () => true, () => assert.fail('must not commit'))
  const recovered = await coordinator.run(
    'node-a',
    async () => ({ token: 'fresh' }),
    () => true,
    () => undefined,
  )

  assert.equal(failed, false)
  assert.equal(recovered, true)
})
