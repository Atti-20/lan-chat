import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(
  new URL('../src/services/conversationSelectionState.ts', import.meta.url),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const { ConversationSelectionGeneration } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('rapid A to B selection prevents late A history from committing', () => {
  const selection = new ConversationSelectionGeneration()
  const first = selection.begin('private:1:2')
  const second = selection.begin('private:1:3')

  assert.equal(selection.isCurrent(first, 'private:1:3'), false)
  assert.equal(selection.isCurrent(second, 'private:1:3'), true)
})

test('Android Back prevents a notification history response from committing', () => {
  const selection = new ConversationSelectionGeneration()
  const notification = selection.begin('private:1:2')

  assert.equal(selection.isCurrent(notification, 'private:1:2'), true)
  assert.equal(selection.isCurrent(notification, null), false)
  assert.equal(selection.owns(notification), true)
})
