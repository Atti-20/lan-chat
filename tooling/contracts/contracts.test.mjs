import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import Ajv2020 from 'ajv/dist/2020.js'
const root = new URL('../../', import.meta.url)
const read = path => JSON.parse(readFileSync(new URL(path, root), 'utf8'))
const schema = read('contracts/websocket/events.schema.json')
const fixtures = read('contracts/websocket/fixtures/core.json')
function validator(direction) {
  return new Ajv2020({ strict: false, allErrors: true }).compile({
    ...schema, $id: undefined,
    anyOf: schema.anyOf.filter(branch => branch['x-direction'] === direction),
  })
}
const validateClient = validator('client'), validateServer = validator('server')

test('recovery subscriptions and body-free hints have explicit directions and strict payloads', () => {
  const epoch = '11111111-1111-4111-8111-111111111111'
  const subscribe = { version: 1, event: 'RECOVERY_SUBSCRIBE', timestamp: 1788868800000,
    payload: { capability: 'meshx.mutation-recovery', protocolVersion: 1, streamEpoch: epoch } }
  assert.equal(validateClient(subscribe), true)
  assert.equal(validateServer(subscribe), false)
  const subscribed = { ...subscribe, event: 'RECOVERY_SUBSCRIBED' }
  assert.equal(validateServer(subscribed), true)
  assert.equal(validateClient(subscribed), false)
  const hint = { version: 1, event: 'MUTATION_AVAILABLE', timestamp: 1788868800000,
    payload: { streamEpoch: epoch, latestCursor: '3' } }
  assert.equal(validateServer(hint), true)
  assert.equal(validateClient(hint), false)
  for (const latestCursor of ['-1', '03', '1.5']) {
    assert.equal(validateServer({ ...hint, payload: { ...hint.payload, latestCursor } }), false)
  }
  assert.equal(validateServer({ ...hint, payload: { ...hint.payload, content: 'must not leak' } }), false)
  assert.equal(validateClient({ ...subscribe, payload: { ...subscribe.payload, protocolVersion: 2 } }), false)
})

test('shared client and server fixtures satisfy their event direction', () => {
  for (const entry of fixtures) {
    const validate = entry.direction === 'client' ? validateClient : validateServer
    assert.equal(validate(entry.frame), true, `${entry.name}: ${JSON.stringify(validate.errors)}`)
  }
})

test('wrong direction, missing deduplication key and invalid burn representation fail', () => {
  const send = fixtures.find(f => f.name === 'text-send').frame
  assert.equal(validateServer(send), false)
  const missing = structuredClone(send)
  delete missing.clientMsgId
  assert.equal(validateClient(missing), false)
  const wrong = structuredClone(send)
  wrong.payload.isBurn = 0
  assert.equal(validateClient(wrong), false)
  const ack = structuredClone(fixtures.find(f => f.name === 'duplicate-ack').frame)
  delete ack.payload.sequence
  assert.equal(validateServer(ack), false)
})

test('future fields remain compatible, while unknown event names need explicit handling', () => {
  const ack = structuredClone(fixtures.find(f => f.name === 'duplicate-ack').frame)
  ack.payload.futureField = 'allowed'
  assert.equal(validateServer(ack), true)
  ack.event = 'UNDOCUMENTED_EVENT'
  assert.equal(validateServer(ack), false)
})

test('REST snapshot preserves auth cookie, protected operations and raw chunk body', () => {
  const api = read('contracts/rest/openapi.json')
  assert.deepEqual(api.paths['/api/v1/auth/login'].post.security, [])
  assert.deepEqual(api.paths['/api/v1/chat/conversations'].get.security, [{ bearerAuth: [] }])
  assert.ok(api.paths['/api/v1/auth/refresh'].post.parameters.some(p => p.in === 'cookie' && p.name === 'lanchat_refresh'))
  const chunk = api.paths['/api/v1/file/uploads/{uploadId}/parts/{partNumber}'].put
  assert.equal(chunk.requestBody.content['application/octet-stream'].schema.format, 'binary')
  assert.equal(api.paths['/api/v1/file/content/{fileName}'].get.responses['200'].content['*/*'].schema.format, 'binary')
})


test('multipart upload and spreadsheet export are binary contracts', () => {
  const api = read('contracts/rest/openapi.json')
  for (const path of ['upload', 'avatar', 'broadcast-image']) {
    const content = api.paths['/api/v1/file/' + path].post.requestBody.content
    assert.deepEqual(Object.keys(content), ['multipart/form-data'])
    assert.equal(content['multipart/form-data'].schema.properties.file.format, 'binary')
  }
  const form = api.paths['/api/v1/file/upload'].post.requestBody.content['multipart/form-data'].schema
  assert.ok(form.required.includes('conversationId'))
  const sheet = api.paths['/api/v1/broadcast/{broadcastId}/export.xlsx'].get.responses['200']
  assert.equal(sheet.content['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'].schema.format, 'binary')
  for (const status of ['401', '403']) {
    assert.equal(api.paths['/api/v1/chat/conversations'].get.responses[status].content['application/json'].schema.$ref, '#/components/schemas/ResultError')
  }
})


test('streaming errors preserve the empty-body alternative', () => {
  const api = read('contracts/rest/openapi.json')
  for (const path of ['/api/v1/file/content/{fileName}', '/api/v1/file/preview/{signToken}']) {
    for (const code of ['401', '403', '404']) {
      assert.equal(api.paths[path].get.responses[code]['x-empty-body-allowed'], true)
    }
  }
})
