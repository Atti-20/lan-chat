import assert from 'node:assert/strict'
import test from 'node:test'
import * as vue from 'vue'
import { createTsLoader, plain } from './helpers/load-ts.mjs'

export function entry(id, state = 'WAITING_NETWORK', createdAt = '2026-09-08T10:00:00Z') {
  return { clientMsgId: id, requestId: `req_${id}`, conversationId: 'private:1:2',
    payload: { contentType: 'text', content: id }, createdAt, retryCount: 2, state }
}

function harness(initial = []) {
  const records = new Map(initial.map(item => [item.clientMsgId, structuredClone(item)]))
  const writes = []
  let failRead = false, failWrite = false
  const storage = {
    async loadOutbox() { if (failRead) throw new Error('read failed'); return [...records.values()].map(item => structuredClone(item)) },
    async saveOutboxEntry(value) {
      if (failWrite) throw new Error('write failed')
      const snapshot = plain(value); records.set(value.clientMsgId, snapshot); writes.push(snapshot)
    },
    async deleteOutboxEntry(id) { if (failWrite) throw new Error('write failed'); records.delete(id) },
  }
  const load = createTsLoader({ externals: { vue }, replacements: {
    'apps/web/src/services/localChatDb.ts': storage,
  } })
  return { api: load('apps/web/src/composables/useOutbox.ts').useOutbox(), records, writes,
    set failRead(value) { failRead = value }, set failWrite(value) { failWrite = value } }
}

test('outbox restores SENDING to waiting, persists recovery and keeps failed entries/manual retry', async () => {
  const h = harness([entry('late', 'SENDING', '2026-09-08T12:00:00Z'), entry('early'), entry('failed', 'FAILED')])
  await h.api.hydrate()
  assert.equal(h.api.hydrated.value, true)
  assert.equal(h.api.pendingCount.value, 2)
  assert.equal(h.api.failedCount.value, 1)
  assert.deepEqual(plain(h.api.readyEntries()).map(item => item.clientMsgId), ['early', 'late'])
  assert.deepEqual(h.writes.map(item => [item.clientMsgId, item.state, item.retryCount]), [['late', 'WAITING_NETWORK', 2]])
  await h.api.retryFailed()
  assert.equal(h.api.failedCount.value, 0)
  assert.equal(h.records.get('failed').retryCount, 2)
  assert.equal(h.records.get('failed').state, 'WAITING_NETWORK')
})

test('duplicate clientMsgId replaces one entry and repeated ACK removal stays idempotent', async () => {
  const h = harness()
  await h.api.enqueue(entry('duplicate-id'))
  await h.api.enqueue({ ...entry('duplicate-id'), payload: { contentType: 'text', content: 'updated' } })
  await h.api.update('duplicate-id', { state: 'SENDING', retryCount: 3 })
  await h.api.update('not-present', { state: 'FAILED' })
  assert.equal(h.api.entries.value.length, 1)
  assert.equal(h.records.size, 1)
  assert.equal(h.records.get('duplicate-id').payload.content, 'updated')
  await h.api.remove('duplicate-id'); await h.api.remove('duplicate-id')
  assert.equal(h.api.entries.value.length, 0)
  assert.equal(h.records.size, 0)
})

test('failed persistence keeps recoverable memory state and later successful writes restore durability', async () => {
  const h = harness()
  h.failWrite = true
  await h.api.enqueue(entry('volatile'))
  assert.equal(h.api.durable.value, false)
  assert.equal(h.api.pendingCount.value, 1)
  assert.equal(h.records.size, 0)
  h.failWrite = false
  await h.api.update('volatile', { state: 'SENDING' })
  assert.equal(h.api.durable.value, true)
  assert.equal(h.records.get('volatile').state, 'SENDING')
})

test('failed hydration rejects without pretending the queue was restored', async () => {
  const h = harness()
  h.failRead = true
  await assert.rejects(h.api.hydrate(), /read failed/)
  assert.equal(h.api.durable.value, false)
  assert.equal(h.api.hydrated.value, false)
})
