import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { createTsLoader } from './helpers/load-ts.mjs'
const core = createTsLoader()('packages/domain-ts/src/recovery.ts')
const targets = JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/mutation-recovery-v1-targets.json', import.meta.url), 'utf8'))
function initial(data = targets.baseState) {
  return { ...core.initialRecoveryState(data.owner), phase: 'ONLINE_SAFE', cursor: data.mutationCursor,
    messages: new Map(data.message ? [[data.message.id, { conversationId: data.access.conversationId, objectVersion: data.message.objectVersion, state: data.message.state }]] : []),
    access: new Map([[data.access.conversationId, { accessVersion: data.access.accessVersion, readAllowed: data.access.readAllowed, sendAllowed: data.access.sendAllowed }]]) }
}
// These are metadata projections, not claims that storage/OS notification target steps passed.
for (const target of targets.cases.filter(item => item.steps.every(step => step.op === 'applyMutation'))) {
  test(`shared mutation planning projection: ${target.id}`, () => {
    let state = initial({ ...targets.baseState, ...target.initialOverride })
    const before = state, expected = target.expected
    const stopped = new Set(), revoked = new Set()
    for (const step of target.steps) {
      const plan = core.planRecoveryMutations(state, step.context, [step.record])
      state = plan.next
      for (const cid of plan.effects.stopAutomaticSend) stopped.add(cid)
      for (const cid of plan.effects.revokeConversations) revoked.add(cid)
    }
    if ('mutationCursor' in expected) assert.equal(state.cursor, expected.mutationCursor)
    if ('messageState' in expected) assert.equal(state.messages.get('message-a')?.state, expected.messageState)
    if ('phase' in expected) assert.equal(state.phase, expected.phase)
    if ('reason' in expected) assert.equal(state.reason, expected.reason)
    if ('readAllowed' in expected) assert.equal(state.access.get('group:21')?.readAllowed, expected.readAllowed)
    if ('sendAllowed' in expected) assert.equal(state.access.get('group:21')?.sendAllowed, expected.sendAllowed)
    if (expected.body === null || expected.cacheVisible === false) assert.equal(core.recoveryMessageVisible(state, 'message-a'), false)
    if (expected.outboxState === 'DROP_BODY_REVOKED') assert.equal(revoked.has('group:21'), true)
    if (expected.autoSend === false) assert.equal(stopped.has('group:21'), true)
    if (expected.noStateWrite) assert.equal(state, before)
    assert.equal(before.cursor, '0')
  })
}
const record = targets.cases[0].steps[0].record
test('HTTP page boundaries cannot jump the cursor or hide missing records', () => {
  const state = initial()
  const page = { records:[record],fromExclusive:'0',through:'1',nextCursor:'1',hasMore:false,floor:'0',latest:'2',streamEpoch:state.context.streamEpoch }
  const apply = value => core.planRecoveryPage(state,state.context,'0','1',value)
  const first = apply(page)
  assert.equal(first.next.cursor,'1')
  assert.equal(core.planRecoveryPage(first.next,state.context,'0','1',page).changed,false)
  for (const value of [{...page,records:[]},{...page,nextCursor:'2'},{...page,hasMore:true},{...page,through:'2'},
    {...page,fromExclusive:'1'},{...page,latest:'0'},{...page,extra:true}]) {
    const rejected=apply(value)
    assert.equal(rejected.changed,false); assert.equal(rejected.next.cursor,'0'); assert.equal(rejected.next.phase,'QUARANTINED')
    assert.equal(rejected.effects.eraseMessages.size,0)
  }
  assert.equal(apply({...page,floor:'1'}).next.reason,'CURSOR_EXPIRED')
  const partial={...page,through:'2',hasMore:true}
  assert.equal(core.planRecoveryPage(state,state.context,'0','2',partial,1).next.cursor,'1')
  assert.equal(core.planRecoveryPage(state,state.context,'0','2',partial,200).changed,false)
  assert.equal(core.planRecoveryPage(state,state.context,'0','0',{...page,records:[],through:'0',nextCursor:'0'}).next,state)
})
test('READY requires the durable cut, complete snapshot and matching generation', () => {
  const state = { ...initial(), phase: 'CATCHING_UP' }
  const reply = { ready: true, acceptedCursor: '0', latest: '0', streamEpoch: state.context.streamEpoch }
  const ready = (value = reply, complete = true, input = state) => core.acceptRecoveryReady(input, input.context, '0', complete, value)
  assert.equal(ready().phase, 'ONLINE_SAFE')
  assert.equal(ready(reply, false).phase, 'CATCHING_UP')
  assert.equal(ready(reply, true, { ...state, rebuild: new Set(['group:21']) }).phase, 'CATCHING_UP')
  assert.equal(ready({ ...reply, ready: false, latest: '1' }).phase, 'CATCHING_UP')
  for (const value of [ { ...reply, latest: '1' }, { ...reply, acceptedCursor: '1', latest: '1' },
    { ...reply, ready: false }, { ...reply, latest: 0 }, { ...reply, content: 'unexpected' } ]) {
    assert.equal(ready(value).phase, 'QUARANTINED')
    assert.equal(ready(value).cursor, '0')
  }
  assert.equal(ready({ ...reply, streamEpoch: 'different' }).reason, 'STREAM_RESET')
  assert.equal(core.acceptRecoveryReady(state, { ...state.context, generation: state.context.generation + 1 }, '0', true, reply), state)
  const blocked = core.storageBlockedRecovery(state)
  assert.equal(ready(reply, true, blocked), blocked)
  const disconnected = core.quarantineRecovery(state, 'DISCONNECTED')
  assert.equal(ready(reply, true, disconnected), disconnected)
})
test('whole-page failure abandons preceding changes and effects', () => {
  const state = initial(), second = { ...record, eventId: '33333333-3333-4333-8333-333333333333', cursor: '3' }
  const plan = core.planRecoveryMutations(state, state.context, [record, second])
  assert.equal(plan.next.cursor, '0'); assert.equal(plan.next.reason, 'CURSOR_GAP')
  assert.equal(plan.next.messages.get('message-a').state, 'NORMAL'); assert.equal(plan.effects.eraseMessages.size, 0)
})
test('same event cannot move to another cursor and forbidden body fields fail closed', () => {
  const state = initial(), first = core.planRecoveryMutations(state, state.context, [record]).next
  assert.equal(core.planRecoveryMutations(first, first.context, [{ ...record, cursor: '2' }]).next.reason, 'PROTOCOL_ERROR')
  assert.equal(core.planRecoveryMutations(state, state.context, [{ ...record, content: 'forbidden' }]).next.cursor, '0')
  for (const value of ['01', '-1', '1.0', '9223372036854775808', 1]) assert.throws(() => core.recoveryDecimal(value))
  assert.equal(core.recoveryDecimal('9223372036854775807'), 9223372036854775807n)
})
test('planning beyond the JavaScript safe integer range preserves exact cursor positions', () => {
  const state = { ...initial(), cursor: '9007199254740991' }
  const plan = core.planRecoveryMutations(state, state.context, [{ ...record, cursor: '9007199254740992' }])
  assert.equal(plan.next.cursor, '9007199254740992'); assert.equal(plan.changed, true)
})
