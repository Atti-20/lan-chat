import assert from 'node:assert/strict'
import test from 'node:test'
import { createTsLoader } from './helpers/load-ts.mjs'
const load = createTsLoader()
const { applyRecoveryData } = load('packages/domain-ts/src/recoveryData.ts')
const queue = load('packages/domain-ts/src/outbox.ts')
const message = { messageId:'m1', clientMsgId:'c1', conversationId:'group:21', fromUserId:7, sequence:1,
  content:'private body', replyToId:'reply', attachmentUrl:'private file', isRecalled:0 }
const entry = {clientMsgId:'c1', requestId:'r1', conversationId:'group:21', createdAt:'2026-09-14T00:00:00Z', retryCount:0,
  state:'SENDING', payload:{contentType:'file', content:'private attachment', isBurn:false, uploadId:'private upload'}}
function plan({ revoke=false, stop=false, erase=false } = {}) {
  return {next:{context:{userId:'7'},messages:new Map([['m1',{state:'RECALLED'}]])},
    effects:{revokeConversations:new Set(revoke?['group:21']:[]),eraseMessages:new Set(erase?['m1']:[]),stopAutomaticSend:new Set(stop?['group:21']:[])}}
}
test('revoke removes existing bodies, attachment fields and pending payload; old IDs never revive', () => {
  const result = applyRecoveryData(plan({revoke:true}), [message], [entry])
  assert.equal(result.messages[0].content, '')
  assert.equal(result.messages[0].attachmentUrl, undefined)
  assert.equal(result.messages[0].replyToId, undefined)
  assert.equal(result.outbox[0].recoveryDisposition, 'DROP_BODY_REVOKED')
  assert.equal(result.outbox[0].payload.uploadId, undefined)
  assert.equal(result.clearPreviews.has('group:21'), true)
  const stored = JSON.parse(JSON.stringify(result.outbox))
  const recovered = queue.recoverOutbox(stored).entries
  const patched = queue.patchOutbox(recovered, 'c1', {state:'WAITING_NETWORK'}).entries
  const overwritten = queue.upsertOutbox(patched, {...entry,state:'WAITING_NETWORK'})
  assert.equal(queue.readyOutboxEntries(overwritten).length, 0)
  assert.equal(overwritten[0].payload.content, '')
  assert.equal(message.content, 'private body')
})
test('send denied retains verified history and draft but grants cannot requeue the old ID', () => {
  const result = applyRecoveryData(plan({stop:true}), [message], [entry])
  assert.equal(result.messages[0], message)
  assert.equal(result.outbox[0].payload.content, entry.payload.content)
  assert.equal(result.outbox[0].recoveryDisposition, 'NEEDS_USER_ACTION')
  const granted = applyRecoveryData(plan(), result.messages, result.outbox)
  assert.equal(queue.readyOutboxEntries(granted.outbox).length, 0)
  assert.equal(granted.outbox[0].recoveryDisposition, 'NEEDS_USER_ACTION')
  const revoked = queue.upsertOutbox(granted.outbox, {...entry,recoveryDisposition:'DROP_BODY_REVOKED'})
  assert.equal(revoked[0].payload.content,'')
  assert.equal(revoked[0].recoveryDisposition,'DROP_BODY_REVOKED')
})
test('terminal message cleanup matches pending IDs only for the authenticated sender', () => {
  assert.equal(applyRecoveryData(plan({erase:true}),[message],[entry]).outbox[0].payload.content,'')
  const other = applyRecoveryData(plan({erase:true}),[{...message,fromUserId:8}],[entry])
  assert.equal(other.outbox[0],entry)
})
