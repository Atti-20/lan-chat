// Explicit offline capacity probe: real Core/coordinator/model sink, injected HTTP and storage.
import assert from 'node:assert/strict'
import {performance} from 'node:perf_hooks'
import {createTsLoader} from '../tests/helpers/load-ts.mjs'
const load=createTsLoader({replacements:{'apps/web/src/services/localChatDb.ts':{}}})
const {RecoveryChatSink}=load('apps/web/src/services/recoveryChatSink.ts')
const {RecoveryCoordinator}=load('apps/web/src/services/recoveryCoordinator.ts')
const count=Number(process.argv[2] ?? 200000)
assert.ok(Number.isInteger(count) && count>=100 && count<=200000 && count%100===0)
const perConversation=count/100,epoch='11111111-1111-4111-8111-111111111111'
let pages=0,bytes=0,published=0,committed=false
const owner={origin:'http://capacity.invalid',userId:'7',generation:1}
const item=index=>index<100
 ? {kind:'CONVERSATION',conversationId:`group:${index+1}`,accessVersion:'1',readAllowed:true,sendAllowed:true,messageSequenceAtH:String(perConversation)}
 : {kind:'MESSAGE',conversationId:`group:${Math.floor((index-100)/perConversation)+1}`,messageId:`m${index-100}`,objectVersion:'1',state:'NORMAL',messageSequence:String((index-100)%perConversation+1),content:'capacity fixture',details:{fromUserId:8,contentType:'text',createTime:'2026-09-20T00:00:00',isBurn:0}}
const sink=new RecoveryChatSink(()=>({outbox:[]}),()=>true,(_,image)=>{published=image.messages.length},()=>{}, {
 loadRecoverySnapshot:async()=>null,
 commitRecoverySnapshot:async(_,revision,snapshot)=>{assert.equal(revision,0);assert.equal(snapshot.messages.length,count);assert.equal(Object.keys(snapshot.recovery.messages).length,count);bytes=Buffer.byteLength(JSON.stringify(snapshot));committed=true;return 1},
})
const transport={capabilities:async()=>({capability:'meshx.mutation-recovery',versions:[1],recordVersion:1,maxPageSize:200}),
 open:async()=>({recoveryId:'capacity',mode:'rebuild',streamEpoch:epoch,startCursor:'0',snapshotBoundary:'0',floor:'0',latest:'0',snapshotId:'capacity'}),
 snapshot:async(_,token)=>{const offset=Number(token??0),end=Math.min(offset+200,count+100);pages++;if(pages%100===0)console.error(`capacity pages=${pages}`);return {snapshotId:'capacity',boundary:'0',items:Array.from({length:end-offset},(_,i)=>item(offset+i)),nextPageToken:end===count+100?null:String(end),snapshotComplete:end===count+100}},
 cut:async()=>({through:'0',floor:'0',streamEpoch:epoch}),mutations:async()=>{throw Error('unexpected mutation page')},
 ready:async()=>{assert.ok(committed);return {ready:true,acceptedCursor:'0',latest:'0',streamEpoch:epoch}},release:async()=>{},
}
const start=performance.now()
const state=await new RecoveryCoordinator(transport,sink,()=>true).rebuild(owner,'capacity')
assert.equal(state.phase,'ONLINE_SAFE');assert.equal(published,count);assert.equal(state.access.size,100)
console.log(JSON.stringify({status:'PASS',messages:count,conversations:100,pages,elapsedMs:Math.round(performance.now()-start),snapshotBytes:bytes,rssBytes:process.memoryUsage().rss,transport:'INJECTED',storage:'JSON_SERIALIZATION_ONLY'}))
