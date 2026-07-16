import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/utils/sequence.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { advanceContiguousSequence } = await import(moduleUrl)

test('advances an ordered or unordered contiguous range', () => {
  assert.equal(advanceContiguousSequence(5, [6, 7, 8]), 8)
  assert.equal(advanceContiguousSequence(5, [8, 6, 7, 6]), 8)
})

test('stops at the first sequence gap', () => {
  assert.equal(advanceContiguousSequence(5, [6, 8, 9]), 6)
  assert.equal(advanceContiguousSequence(5, [10]), 5)
})

test('does not treat a latest-history page as a receive cursor', () => {
  assert.equal(advanceContiguousSequence(0, [951, 952, 1000]), 0)
})
