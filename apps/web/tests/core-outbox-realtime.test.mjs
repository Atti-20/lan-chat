import assert from 'node:assert/strict'
import test from 'node:test'
import { createTsLoader, plain } from './helpers/load-ts.mjs'

const load = createTsLoader()
const outbox = load('packages/domain-ts/src/outbox.ts')
const realtime = load('packages/domain-ts/src/realtime.ts')
const entry = (id, state, createdAt) => ({ clientMsgId: id, requestId: `req_${id}`,
  conversationId: 'private:1:2', payload: { contentType: 'text', content: id }, retryCount: 2, state, createdAt })

test('pure outbox recovery retains idempotency and retries without changing the persisted input', () => {
  const stored = [entry('late', 'SENDING', '2026-09-08T02:00:00Z'), entry('early', 'WAITING_NETWORK', '2026-09-08T01:00:00Z'), entry('failed', 'FAILED', '2026-09-08T00:00:00Z')]
  const before = plain(stored)
  const recovered = outbox.recoverOutbox(stored)
  assert.deepEqual(plain(stored), before)
  assert.deepEqual(plain(recovered.recovered), [{ ...before[0], state: 'WAITING_NETWORK' }])
  assert.deepEqual(plain(outbox.readyOutboxEntries(recovered.entries)).map(value => value.clientMsgId), ['early', 'late'])
  const updated = outbox.upsertOutbox(recovered.entries, { ...stored[0], retryCount: 3 })
  assert.equal(updated.length, 3)
  assert.equal(outbox.patchOutbox(updated, 'missing', { state: 'FAILED' }), null)
  const patched = outbox.patchOutbox(updated, 'late', { state: 'FAILED', lastError: 'ACK timeout' })
  assert.equal(patched.changed.requestId, 'req_late')
  assert.equal(patched.changed.retryCount, 3)
  assert.equal(patched.changed.lastError, 'ACK timeout')
})

test('reconnect delays preserve existing cap/jitter and SYNCING is not business-online', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 20].map(attempt => realtime.reconnectDelay(attempt, 0)), [1000, 2000, 4000, 8000, 16000, 30000, 30000])
  assert.equal(realtime.reconnectDelay(1, 0.5), 1125)
  assert.equal(realtime.reconnectDelay(100, 0.9999), 37499)
  for (const state of ['OFFLINE', 'CONNECTING', 'AUTHENTICATING', 'SYNCING', 'RECONNECTING']) assert.equal(realtime.isRealtimeOnline(state), false)
  assert.equal(realtime.isRealtimeOnline('ONLINE'), true)
  assert.equal(realtime.isRealtimeOnline('DEGRADED'), true)
})
