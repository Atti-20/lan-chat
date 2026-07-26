import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(
  new URL('../capacitor.config.ts', import.meta.url),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { default: config } = await import(moduleUrl)

test('Capacitor never logs native authentication plugin payloads', async () => {
  assert.equal(config.loggingBehavior, 'none')
  assert.equal(config.android?.loggingBehavior, 'none')

  const generated = JSON.parse(await readFile(
    new URL('../android/app/src/main/assets/capacitor.config.json', import.meta.url),
    'utf8',
  ))
  assert.equal(generated.loggingBehavior, 'none')
  assert.equal(generated.android?.loggingBehavior, 'none')
})
