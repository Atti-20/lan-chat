// Artifact validation only. Does not implement or run Java/TS/Dart recovery business logic.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import Ajv2020 from '../../../tooling/contracts/node_modules/ajv/dist/2020.js'
const read = name => JSON.parse(readFileSync(new URL(name, import.meta.url), 'utf8'))
const schema = read('mutation-record.schema.json')
const validate = new Ajv2020({ strict: true, allErrors: true }).compile(schema)
const bounded = record => ['cursor', 'objectVersion', 'accessVersion'].every(key => record[key] == null || typeof record[key] === 'string' && /^[0-9]+$/.test(record[key]) && BigInt(record[key]) <= 9223372036854775807n)
const accepts = record => validate(record) && bounded(record)
const examples = read('record-examples.json')
for (const record of examples.valid) assert.ok(accepts(record), JSON.stringify(validate.errors))
for (const { id, record } of examples.invalid) assert.equal(Boolean(accepts(record)), false, id)
const targets = read('../../../contracts/test-vectors/mutation-recovery-v1-targets.json')
assert.equal(targets.targetStatus, 'TARGET_PENDING_APPROVAL')
const ids = new Set(targets.cases.map(c => c.id))
assert.equal(ids.size, targets.cases.length)
const required = ['duplicate-mutation','mutation-before-original','stale-original-after-tombstone','stale-snapshot','reconnect-replay','old-connection','old-account','old-origin','unknown-negotiated-secure-state','conversation-removed','group-access-revoked','friend-deleted-read-allowed-send-denied','pending-outbox-does-not-revive','cursor-expired','rebuild-required','persistence-failure','restart-after-mutation','pagination-time-mutation','markRead-foreground','markRead-background','markRead-unopened','notification-suppression']
for (const id of required) assert.ok(ids.has(id), `Missing target ${id}`)
for (const c of targets.cases) {
  assert.equal(c.runtimeVerification, 'NOT_RUN')
  assert.ok(c.steps.length && Object.keys(c.expected).length, `Empty target ${c.id}`)
  for (const step of c.steps) if (step.record && !['unknown-negotiated-secure-state'].includes(c.id)) assert.ok(accepts(step.record), c.id)
  if (['duplicate-mutation','mutation-before-original','stale-original-after-tombstone','stale-snapshot','message-burned','message-unavailable','restart-after-mutation'].includes(c.id)) assert.equal(c.expected.body, null, `Unsafe target ${c.id}`)
  if (['old-connection','old-account','old-origin','cursor-expired','rebuild-required','persistence-failure','unknown-negotiated-secure-state'].includes(c.id)) assert.equal(c.expected.mutationCursor, '0', c.id)
}
console.log(JSON.stringify({ artifactValidation: 'PASS', schemaPositive: examples.valid.length, schemaNegative: examples.invalid.length, targetVectors: targets.cases.length, requiredCoverage: required.length, productionBehavior: 'NOT_RUN', approval: 'PENDING' }))
