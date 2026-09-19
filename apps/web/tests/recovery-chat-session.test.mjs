import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import {createTsLoader,plain,deferred} from './helpers/load-ts.mjs'
const load=createTsLoader({replacements:{'apps/web/src/services/localChatDb.ts':{}}})
const {RecoveryChatSession}=load('apps/web/src/services/recoveryChatSession.ts')
const {RecoveryNotificationDispatcher}=load('apps/web/src/services/recoveryNotifications.ts')
const f=JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/recovery-snapshot-pages.json',import.meta.url)))
function harness() {
  let disk, revision=0, failing=false, gate
  const transport={capabilities:async()=>({capability:'meshx.mutation-recovery',versions:[1],recordVersion:1,maxPageSize:100}),
    open:async()=>({recoveryId:'session',mode:'rebuild',streamEpoch:f.context.streamEpoch,startCursor:'0',snapshotBoundary:'0',floor:'0',latest:'0',snapshotId:'snapshot'}),
    snapshot:async()=>({snapshotId:'snapshot',boundary:'0',items:[f.directory,f.message],nextPageToken:null,snapshotComplete:true}),
    cut:async()=>({through:'0',floor:'0',streamEpoch:f.context.streamEpoch}),mutations:async()=>{throw Error('unexpected')},
    ready:async()=>({ready:true,acceptedCursor:'0',latest:'0',streamEpoch:f.context.streamEpoch}),release:async()=>{}}
  const storage={loadRecoverySnapshot:async()=>disk?{revision,snapshot:disk}:null,
    commitRecoverySnapshot:async(_,expected,snapshot)=>{if(gate)await gate.promise;if(failing)throw Error('disk full');assert.equal(expected,revision);disk=plain(snapshot);return ++revision}}
  const session=new RecoveryChatSession(transport,f.context,()=>true,()=>[],()=>{},storage)
  return {session,transport,storage,disk:()=>disk,fail:()=>{failing=true},hold:()=>gate=deferred()}
}
const entry={clientMsgId:'pending',requestId:'request',conversationId:'group:21',createdAt:'2026-09-19T00:00:00Z',retryCount:0,state:'WAITING_NETWORK',payload:{contentType:'text',content:'draft',isBurn:false}}
test('session persists new outbox alongside verified history and holds it across recovery',async()=>{
  const h=harness();await h.session.rebuild('first')
  assert.equal(h.session.canSend('group:21'),true);assert.equal(h.session.canSend('group:99'),false)
  await h.session.writeOutbox([entry])
  assert.equal(h.disk().messages[0].content,'fixture body');assert.equal(h.disk().outbox[0].payload.content,'draft')
  h.session.quarantine();assert.equal(h.session.canSend('group:21'),false)
  await h.session.rebuild('next')
  assert.equal(h.session.image.outbox[0].recoveryDisposition,'NEEDS_USER_ACTION')
})
test('outbox persistence failure blocks sending without claiming the failed generation',async()=>{
  const h=harness();await h.session.rebuild('first');h.fail()
  await assert.rejects(h.session.writeOutbox([entry]),/disk full/)
  assert.equal(h.session.state.phase,'STORAGE_BLOCKED');assert.equal(h.session.canSend('group:21'),false)
  assert.deepEqual(h.disk().outbox,[])
})
test('disconnect during persistence cannot publish completion as online',async()=>{
  const h=harness();await h.session.rebuild('first');const gate=h.hold()
  const writing=h.session.writeOutbox([entry]);const rejection=assert.rejects(writing,/STALE_RECOVERY|RECOVERY_REQUIRED/)
  await Promise.resolve();h.session.quarantine();gate.resolve();await rejection
  assert.equal(h.session.safe,false)
})

test('concurrent user sends are merged against the last durable queue',async()=>{
  const h=harness();await h.session.rebuild('first')
  await Promise.all([h.session.saveOutbox(entry),h.session.saveOutbox({...entry,clientMsgId:'second',requestId:'second'})])
  assert.deepEqual(h.disk().outbox.map(e=>e.clientMsgId),['pending','second'])
  await h.session.deleteOutbox('pending')
  assert.deepEqual(h.disk().outbox.map(e=>e.clientMsgId),['second'])
})

function incomingPage(terminal=false) {
 const row={...f.message,messageId:'live',messageSequence:'2',details:{...f.message.details,fromUserId:8}}
 if(terminal) {row.state='RECALLED';row.objectVersion='2';delete row.content;delete row.details}
 return {snapshotId:'snapshot',boundary:'0',items:[{...f.directory,messageSequenceAtH:'2'},f.message,row],nextPageToken:null,snapshotComplete:true}
}
test('durable notification cancellation survives OS failure and session restart',async()=>{
 const h=harness(),calls=[];let failed=false
 const port={cancelAll:async()=>calls.push('all'),show:async id=>{assert.equal(h.disk().notificationRecovery.effects[0].kind,'SHOW');calls.push(`show:${id}`)},cancel:async id=>{calls.push(`cancel:${id}`);if(failed)throw Error('OS unavailable')}}
 const dispatch=new RecoveryNotificationDispatcher(h.session,port)
 await h.session.rebuild('initial');await dispatch.drain();assert.deepEqual(calls,['all'])
 h.session.noteLiveCandidate('live');h.transport.snapshot=async()=>incomingPage()
 await h.session.rebuild('incoming');assert.equal(h.disk().notificationRecovery.effects[0].kind,'SHOW');await dispatch.drain()
 assert.deepEqual(calls,['all','show:live']);assert.equal(h.disk().notificationRecovery.effects.length,0)
 h.transport.snapshot=async()=>incomingPage(true);await h.session.rebuild('recalled');failed=true
 await assert.rejects(dispatch.drain(),/OS unavailable/);assert.equal(h.disk().notificationRecovery.effects[0].kind,'CANCEL');assert.equal(h.disk().notificationRecovery.routes.length,0)
 const restarted=new RecoveryChatSession(h.transport,f.context,()=>true,()=>[],()=>{},h.storage);await restarted.rebuild('restart');failed=false
 await new RecoveryNotificationDispatcher(restarted,port).drain();assert.equal(h.disk().notificationRecovery.effects.length,0)
})
test('late OS SHOW completion is cancelled after revoke and cannot acknowledge the newer generation',async()=>{
 const h=harness(),gate=deferred(),entered=deferred(),calls=[]
 await h.session.rebuild('first');await h.session.acknowledgeNotification('migration')
 h.session.noteLiveCandidate('live');h.transport.snapshot=async()=>incomingPage();await h.session.rebuild('incoming')
 const dispatcher=new RecoveryNotificationDispatcher(h.session,{cancelAll:async()=>{},show:async()=>{entered.resolve();await gate.promise},cancel:async id=>calls.push(id)})
 const running=dispatcher.drain();await entered.promise
 h.transport.snapshot=async()=>incomingPage(true);await h.session.rebuild('revoke');gate.resolve();await running
 assert.deepEqual(calls,['live']);assert.equal(h.disk().notificationRecovery.effects[0].kind,'CANCEL')
 await dispatcher.drain();assert.deepEqual(calls,['live','live']);assert.equal(h.disk().notificationRecovery.effects.length,0)
})
