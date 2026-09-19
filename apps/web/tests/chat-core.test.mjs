import assert from 'node:assert/strict'
import test from 'node:test'
import { createTsLoader, plain } from './helpers/load-ts.mjs'

// These same cases passed against the original composable before extraction.
// Run the shared rules without Vue, DOM globals or a storage runtime.
const core = createTsLoader()('packages/domain-ts/src/messages.ts')
function rules(userId) {
  return {
    normalizeMessage: message => core.normalizeMessage(message, userId),
    mergeMessages: (current, incoming) => core.mergeMessages(current, incoming, userId),
    sortMessages: core.sortMessages,
  }
}

const message = (id, extra = {}) => ({ messageId: id, fromUserId: 1, type: 'text', content: id,
  conversationId: 'private:1:2', status: 0, ...extra })

test('chat model mapping preserves current-user state, server flags and timestamp precedence', () => {
  const own = rules(1).normalizeMessage(message('m1', { type: 'chat', contentType: 'image', isBurn: true,
    isRecalled: '1', status: 1, timestamp: '2026-09-08T00:01:00Z', clientCreatedAt: 'older' }))
  assert.equal(own.type, 'image'); assert.equal(own.contentType, 'image')
  assert.equal(own.isBurn, 1); assert.equal(own.isRecalled, 1)
  assert.equal(own.deliveryState, 'READ'); assert.equal(own.createTime, '2026-09-08T00:01:00Z')
  assert.equal(rules(2).normalizeMessage(message('m1')).deliveryState, 'DELIVERED')
  assert.equal(rules(1).normalizeMessage(message('m1', { deliveryState: 'FAILED' })).deliveryState, 'FAILED')
})

test('ACK then duplicate delivery reconciles optimistic identity to one visible message', () => {
  const core = rules(1)
  const optimistic = message('local:msg-id-01', { clientMsgId: 'msg-id-01', deliveryState: 'SENDING', clientCreatedAt: '2026-09-08T00:00:00Z' })
  const accepted = message('server-id-01', { clientMsgId: 'msg-id-01', sequence: 4, deliveryState: 'SENT', createTime: '2026-09-08T00:00:01Z' })
  const before = plain(optimistic)
  const merged = core.mergeMessages([optimistic], [accepted, { ...accepted, content: 'canonical' }])
  assert.equal(merged.length, 1)
  assert.equal(merged[0].messageId, 'server-id-01')
  assert.equal(merged[0].sequence, 4); assert.equal(merged[0].content, 'canonical')
  assert.deepEqual(plain(optimistic), before)
  const repeated = core.mergeMessages(merged, [accepted])
  assert.equal(repeated[0].clientCreatedAt, optimistic.clientCreatedAt)
  assert.deepEqual(plain(core.mergeMessages(repeated, [accepted])), plain(repeated))
})

test('message sort keeps sequenced history before pending items and does not reorder input', () => {
  const core = rules(1)
  const input = [message('pending-late', { clientCreatedAt: '2026-09-08T00:00:02Z' }),
    message('two', { sequence: 2 }), message('pending-early', { createTime: '2026-09-08T00:00:01Z' }), message('one', { sequence: 1 })]
  assert.deepEqual(plain(core.sortMessages(input)).map(item => item.messageId), ['one', 'two', 'pending-early', 'pending-late'])
  assert.equal(input[0].messageId, 'pending-late')
})
