import assert from 'node:assert/strict'
import {webcrypto} from 'node:crypto'
import {createTsLoader,plain} from '../tests/helpers/load-ts.mjs'
const origin=process.env.MESHX_RECOVERY_HTTP_ORIGIN
assert.match(origin || '',/^http:\/\/127\.0\.0\.1:\d+$/)
const load=createTsLoader({globals:{fetch,Headers,FormData,URLSearchParams,crypto:webcrypto,AbortController,setTimeout,clearTimeout,navigator:{userAgent:'MeshX fixture'}},replacements:{
  'apps/web/src/services/localChatDb.ts':{},
  'apps/web/src/platform/nativeBridge.ts':{nativeBridge:{runtime:()=> 'web'}},
  'apps/web/src/platform/nativeTransport.ts':{nodeFetch:fetch},
  'apps/web/src/platform/nodeContext.ts':{currentNodeKey:()=>origin,currentNodeOrigin:()=>origin,currentNodeApiBasePath:()=>'/api/v1',apiUrl:path=>origin+'/api/v1'+path},
  'apps/web/src/utils/storage.ts':{readSession:()=>({userId:7,token:'fixture'}),writeSession:()=>{},clearSession:()=>{}},
}})
const {api}=load('apps/web/src/services/api.ts')
const {RecoveryCoordinator}=load('apps/web/src/services/recoveryCoordinator.ts')
const {RecoveryChatSink}=load('apps/web/src/services/recoveryChatSink.ts')
const capabilities=await api.recovery.capabilities()
assert.deepEqual(capabilities.versions,[]) // Production certification remains closed.
const transport={...api.recovery,capabilities:async()=>({...capabilities,versions:[1]})}
let snapshots=0
for(const name of ['open','snapshot','cut','mutations','ready']) {
 const original=transport[name]
 transport[name]=async(...args)=>{if(name==='snapshot')snapshots++;const result=await original(...args);console.log(JSON.stringify({step:name,...(name==='snapshot'?{boundary:result.boundary,count:result.items?.length,complete:result.snapshotComplete,first:result.items?.slice(0,2).map(({content,...item})=>item)}:result)}));return result}
}
let disk,published,revision=0
const sink=new RecoveryChatSink(()=>({outbox:[]}),()=>true,(_,image)=>published=image,()=>{},
 {loadRecoverySnapshot:async()=>null,commitRecoverySnapshot:async(_,expected,image)=>{assert.equal(expected,revision);disk=plain(image);return ++revision}})
const runner=new RecoveryCoordinator(transport,sink,()=>true)
const result=await runner.rebuild({origin,userId:'7',generation:1},'web-live-http')
assert.equal(result?.phase,'ONLINE_SAFE',runner.reason)
assert.equal(disk.recovery.cursor,'1');assert.equal(disk.messages.length,204)
assert.equal(disk.tombstones[0].messageId,'group:21-1');assert.equal(disk.tombstones[0].state,'RECALLED')
assert.equal(JSON.stringify(disk).includes('sensitive-first-body'),false)
assert.equal(published.messages.length,204)
console.log(JSON.stringify({client:'web',http:'Spring/Tomcat',database:'MySQL',snapshotMessages:205,normalAfterRecall:204,cursor:disk.recovery.cursor,productionCapabilityAdvertised:false,result:'PASS'}))

const snapshotCalls=snapshots
const resumed=await runner.rebuild({origin,userId:'7',generation:1},'web-live-resume',result)
assert.equal(resumed?.phase,'ONLINE_SAFE',runner.reason)
assert.equal(disk.recovery.cursor,'2');assert.equal(disk.tombstones[0].state,'UNAVAILABLE')
assert.equal(snapshots,snapshotCalls,'resume re-downloaded snapshot')
console.log(JSON.stringify({resume:'PASS',cursor:'2',additionalSnapshotRequests:0}))
