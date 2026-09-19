import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import {createTsLoader,plain,deferred} from './helpers/load-ts.mjs'
const load=createTsLoader({replacements:{'apps/web/src/services/localChatDb.ts':{}}})
const {RecoveryChatSink}=load('apps/web/src/services/recoveryChatSink.ts')
const {RecoveryCoordinator}=load('apps/web/src/services/recoveryCoordinator.ts')
const f=JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/recovery-snapshot-pages.json',import.meta.url)))
function harness(fail=false) {
  let disk, published, readyCalls=0, revision=0, seeded=true
  const queue=cid=>({conversationId:cid,clientMsgId:`pending-${cid}`,requestId:'request',createdAt:'2026-09-14T00:00:00Z',retryCount:0,state:'WAITING_NETWORK',payload:{contentType:'text',content:`draft-${cid}`,isBurn:false}})
  const sink=new RecoveryChatSink(()=>({outbox:seeded?[queue('group:99'),queue('group:21')]:[]}),()=>true,(_,image)=>published=image,()=>{},
    {loadRecoverySnapshot:async()=>disk?{revision,snapshot:disk}:null,commitRecoverySnapshot:async(_,expected,snapshot)=>{assert.equal(expected,revision);if(fail)throw Error('disk full');disk=plain(snapshot);return ++revision}})
  const transport={capabilities:async()=>({capability:'meshx.mutation-recovery',versions:[1],recordVersion:1,maxPageSize:100}),
    open:async()=>({recoveryId:'session',mode:'rebuild',streamEpoch:f.context.streamEpoch,startCursor:'0',snapshotBoundary:'0',floor:'0',latest:'0',snapshotId:'snapshot'}),
    snapshot:async()=>({snapshotId:'snapshot',boundary:'0',items:[f.directory,f.message],nextPageToken:null,snapshotComplete:true}),
    cut:async()=>({through:'1',floor:'0',streamEpoch:f.context.streamEpoch}),
    mutations:async()=>({fromExclusive:'0',through:'1',nextCursor:'1',hasMore:false,floor:'0',latest:'1',streamEpoch:f.context.streamEpoch,
      records:[{recordVersion:1,eventId:'22222222-2222-4222-8222-222222222222',streamEpoch:f.context.streamEpoch,cursor:'1',type:'MESSAGE_RECALLED',conversationId:'group:21',messageId:'message-a',objectVersion:'2',committedAt:'2026-09-14T00:00:00.000Z'}]}),
    ready:async()=>{readyCalls++;assert.ok(disk);return {ready:true,acceptedCursor:'1',latest:'1',streamEpoch:f.context.streamEpoch}},release:async()=>{}}
  return {runner:new RecoveryCoordinator(transport,sink,()=>true),sink,disk:()=>disk,published:()=>published,readyCalls:()=>readyCalls,restart:()=>{seeded=false}}
}
test('coordinator and actual model sink remove terminal bodies before durable READY',async()=>{
  const h=harness(),result=await h.runner.rebuild(f.context,'key')
  assert.equal(result.phase,'ONLINE_SAFE');assert.equal(h.readyCalls(),1)
  const disk=h.disk()
  assert.equal(disk.recovery.cursor,'1');assert.equal(disk.messages.length,0)
  assert.equal(disk.tombstones[0].state,'RECALLED');assert.equal(disk.tombstones[0].messageSequence,'1')
  assert.equal(JSON.stringify(disk).includes('fixture body'),false)
  assert.equal(JSON.stringify(disk).includes('draft-group:99'),false)
  assert.equal(disk.outbox[0].recoveryDisposition,'DROP_BODY_REVOKED')
  assert.equal(disk.outbox[1].recoveryDisposition,'NEEDS_USER_ACTION');assert.equal(disk.outbox[1].payload.content,'draft-group:21')
  assert.deepEqual(plain(h.published().messages),[])
  h.restart()
  assert.equal((await h.runner.rebuild(f.context,'restart-key')).phase,'ONLINE_SAFE')
  assert.equal(h.disk().outbox.length,2)
  assert.equal(h.disk().outbox[1].payload.content,'draft-group:21')
  h.sink.quarantine('DISCONNECTED')
  assert.throws(()=>h.sink.publish(f.context,result),/STALE_RECOVERY/)
})
test('failed actual sink commit never sends READY or publishes',async()=>{
  const h=harness(true),result=await h.runner.rebuild(f.context,'key')
  assert.equal(result.phase,'STORAGE_BLOCKED');assert.equal(h.readyCalls(),0)
  assert.equal(h.disk(),undefined);assert.equal(h.published(),undefined)
})

test('same-owner rebuild invalidates late commit completion from the previous run',async()=>{
  const gate=deferred(),entered=deferred()
  const {initialRecoveryState}=load('packages/domain-ts/src/recovery.ts')
  const state={...initialRecoveryState(f.context),phase:'CATCHING_UP'}
  const sink=new RecoveryChatSink(()=>({outbox:[]}),()=>true,()=>{},()=>{},
    {loadRecoverySnapshot:async()=>null,commitRecoverySnapshot:async()=>{entered.resolve();await gate.promise;return 1}})
  await sink.begin(f.context)
  const old=sink.commit(f.context,state)
  const rejection=assert.rejects(old,/STALE_RECOVERY/)
  await entered.promise
  sink.quarantine('DISCONNECTED');await sink.begin(f.context)
  gate.resolve();await rejection
  assert.throws(()=>sink.publish(f.context,{...state,phase:'ONLINE_SAFE'}),/PROTOCOL_ERROR/)
})
