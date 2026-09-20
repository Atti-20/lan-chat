import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/utils/sha256.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { sha256Blob } = await import(moduleUrl)

test('matches the standard empty, abc, and million-a SHA-256 vectors', async () => {
  assert.equal(
    await sha256Blob(new Blob([])),
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  )
  assert.equal(
    await sha256Blob(new Blob(['abc'])),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  )
  assert.equal(
    await sha256Blob(new Blob(['a'.repeat(1_000_000)])),
    'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0',
  )
})

test('hashes correctly across the internal two-MiB read boundary', async () => {
  const bytes = Buffer.alloc(2 * 1024 * 1024 + 17)
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = index % 251
  }
  const expected = createHash('sha256').update(bytes).digest('hex')
  assert.equal(await sha256Blob(new Blob([bytes])), expected)
})

test('honors an already-aborted upload signal', async () => {
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(
    sha256Blob(new Blob(['cancelled']), controller.signal),
    (cause) => cause instanceof DOMException && cause.name === 'AbortError',
  )
})
