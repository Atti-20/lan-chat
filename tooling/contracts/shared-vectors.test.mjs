import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import Ajv2020 from 'ajv/dist/2020.js'
import Ajv from 'ajv'
import ts from 'typescript'

const read = p => JSON.parse(readFileSync(new URL('../../' + p, import.meta.url)))
const vectors = read('contracts/fixtures/core-v1.json')
const events = read('contracts/websocket/events.schema.json')
const api = read('contracts/rest/openapi.json')

test('shared canonical WebSocket vectors include valid and invalid cases in both directions', () => {
  const envelope = new Ajv2020({ strict: false }).compile(read('contracts/websocket/envelope.schema.json'))
  for (const v of vectors.frames) assert.equal(envelope(v.frame), v.baseValid, `${v.id}: base envelope`)
  for (const direction of ['client', 'server']) {
    const validate = new Ajv2020({ strict: false }).compile({
      ...events, $id: undefined, anyOf: events.anyOf.filter(b => b['x-direction'] === direction),
    })
    for (const v of vectors.frames.filter(v => v.direction === direction)) {
      assert.equal(validate(v.frame), v.valid, `${v.id}: ${JSON.stringify(validate.errors)}`)
    }
  }
})

test('shared REST examples preserve missing, nullable, required and JSON field types', () => {
  const ajv = new Ajv({ strict: false, validateFormats: false })
  for (const v of vectors.rest) {
    const validate = ajv.compile({ $ref: '#/components/schemas/' + v.model, components: api.components })
    assert.equal(validate(v.value), v.valid, `${v.id}: ${JSON.stringify(validate.errors)}`)
  }
})

test('actual shared TS cursor never advances through a missing sequence', async () => {
  const source = readFileSync(new URL('../../packages/domain-ts/src/sequence.ts', import.meta.url), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
  const { advanceContiguousSequence } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'))
  for (const v of vectors.sequences) assert.equal(advanceContiguousSequence(v.current, v.candidates), v.expected, v.id)
})
