import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import {createTsLoader,deferred} from './helpers/load-ts.mjs'
const {RecoveryCoordinator}=createTsLoader()('apps/web/src/services/recoveryCoordinator.ts')
const f=JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/recovery-snapshot-pages.json',import.meta.url)))
const owner={origin:f.context.origin,userId:f.context.userId,generation:1}
function harness() {
  const events=[];let cuts=0,readyCalls=0,current=true
  const sink={begin:async()=>{events.push('begin')},snapshot:async()=>{events.push('snapshot')},mutations:async(_,p)=>{events.push(`apply:${p.next.cursor}`)},
    commit:async(_,s)=>{events.push(`commit:${s.cursor}`)},publish:(_,s)=>events.push(`publish:${s.cursor}`),quarantine:r=>events.push(r)}
  const transport={capabilities:async()=>({capability:'meshx.mutation-recovery',versions:[1],recordVersion:1,maxPageSize:100}),
    open:async()=>({recoveryId:'session',mode:'rebuild',streamEpoch:f.context.streamEpoch,startCursor:'0',snapshotBoundary:'0',floor:'0',latest:'0',snapshotId:'snapshot'}),
    snapshot:async()=>({snapshotId:'snapshot',boundary:'0',items:[f.directory,f.message],nextPageToken:null,snapshotComplete:true}),
    cut:async()=>({through:String(++cuts),floor:'0',streamEpoch:f.context.streamEpoch}),
    mutations:async(_,after,through)=>({fromExclusive:after,through,nextCursor:through,hasMore:false,floor:'0',latest:through,streamEpoch:f.context.streamEpoch,
      records:[{recordVersion:1,eventId:through==='1'?'22222222-2222-4222-8222-222222222222':'33333333-3333-4333-8333-333333333333',streamEpoch:f.context.streamEpoch,
        cursor:through,type:through==='1'?'MESSAGE_RECALLED':'MESSAGE_UNAVAILABLE',conversationId:'group:21',messageId:'message-a',objectVersion:String(Number(through)+1),committedAt:'2026-09-14T00:00:00.000Z'}]}),
    ready:async(_,cursor)=>{readyCalls++;events.push(`ready:${cursor}`);return {ready:readyCalls>1,acceptedCursor:cursor,latest:'2',streamEpoch:f.context.streamEpoch}},release:async()=>{events.push('release')}}
  const runner=new RecoveryCoordinator(transport,sink,()=>current)
  return {runner,sink,transport,events,switchOwner:()=>{current=false},readyCalls:()=>readyCalls}
}
test('snapshot -> replay -> durable commit -> READY; newer latest requires another committed round',async()=>{
  const h=harness(),result=await h.runner.rebuild(owner,'request-key')
  assert.equal(result.phase,'ONLINE_SAFE');assert.equal(h.runner.durableCursor,'2')
  assert.deepEqual(h.events,['RECOVERING','begin','snapshot','apply:1','commit:1','ready:1','apply:2','commit:2','ready:2','publish:2','release'])
})
test('pending or failed commit cannot call READY or publish',async()=>{
  const h=harness(),gate=deferred(),entered=deferred()
  h.sink.commit=async()=>{entered.resolve();await gate.promise;throw new Error('disk full')}
  const running=h.runner.rebuild(owner,'request-key');await entered.promise
  assert.equal(h.readyCalls(),0);assert.equal(h.runner.durableCursor,null)
  gate.resolve();const result=await running
  assert.equal(result.phase,'STORAGE_BLOCKED');assert.equal(h.readyCalls(),0);assert.equal(h.runner.durableCursor,null)
  assert.equal(h.events.some(e=>e.startsWith('publish:')),false)
})
test('owner switch during commit never publishes or sends requests as the new owner',async()=>{
  const h=harness(),gate=deferred(),entered=deferred()
  h.sink.commit=async()=>{entered.resolve();await gate.promise}
  const running=h.runner.rebuild(owner,'request-key');await entered.promise;h.switchOwner();h.runner.cancel();gate.resolve()
  assert.equal(await running,null);assert.equal(h.readyCalls(),0);assert.equal(h.events.includes('release'),false)
})
test('unsupported capability never opens or stages a recovery',async()=>{
  const h=harness();h.transport.capabilities=async()=>({capability:'meshx.mutation-recovery',versions:[],recordVersion:1,maxPageSize:100})
  assert.equal(await h.runner.rebuild(owner,'request-key'),null);assert.equal(h.runner.reason,'BLOCKED_UPGRADE');assert.equal(h.events.includes('begin'),false)
})
test('cancellation during session cleanup cannot return stale ONLINE_SAFE',async()=>{
  const h=harness(),entered=deferred(),gate=deferred()
  h.transport.release=async()=>{entered.resolve();await gate.promise}
  const running=h.runner.rebuild(owner,'request-key');await entered.promise
  h.switchOwner();h.runner.cancel();gate.resolve()
  assert.equal(await running,null);assert.equal(h.runner.state.phase,'QUARANTINED')
})

test('resume keeps committed cursor and skips snapshot download',async()=>{
  const h=harness(),first=await h.runner.rebuild(owner,'first')
  h.sink.resume=async(_,state)=>h.events.push(`resume:${state.cursor}`)
  h.transport.open=async(input)=>{
    assert.equal(input.mode,'resume');assert.equal(input.cursor.position,'2');assert.equal(input.cursor.streamEpoch,f.context.streamEpoch)
    return {recoveryId:'resume',mode:'resume',streamEpoch:f.context.streamEpoch,startCursor:'2',floor:'0',latest:'2'}
  }
  h.transport.cut=async()=>({through:'2',floor:'0',streamEpoch:f.context.streamEpoch})
  h.transport.snapshot=async()=>{throw Error('resume must not download snapshots')}
  const second=await h.runner.rebuild(owner,'resume',first)
  assert.equal(second.phase,'ONLINE_SAFE');assert.equal(h.runner.durableCursor,'2')
  assert.ok(h.events.includes('resume:2'))
})
