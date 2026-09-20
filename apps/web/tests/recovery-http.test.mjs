import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {webcrypto} from 'node:crypto'
import test from 'node:test'
import {createTsLoader} from './helpers/load-ts.mjs'
test('recovery HTTP retains request key during refresh and exposes error data',async () => {
  const opens=[]
  let refreshes=0, query, snapshotQuery, readyBody
  const paths=[]
  const server=createServer(async(req,res)=>{
    const path=new URL(req.url,'http://local')
    paths.push(path.pathname)
    const reply=(data,status=200,reason)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(status===204?undefined:JSON.stringify({code:status,msg:reason,data}))}
    if(path.pathname.endsWith('/auth/refresh')) {refreshes++;reply({userId:7,token:'new'})}
    else if(path.pathname.endsWith('/sessions')) {
      let body='';for await(const chunk of req) body+=chunk
      opens.push({key:req.headers['idempotency-key'],body});reply({recoveryId:'session-a'},opens.length===1?401:200)
    } else if(path.pathname.endsWith('/mutations')) {
      query=Object.fromEntries(path.searchParams);reply({reason:'CURSOR_EXPIRED',floor:'9007199254740993',rebuildRequired:true},409,'CURSOR_EXPIRED')
    } else if(path.pathname.endsWith('/snapshot')) {snapshotQuery=Object.fromEntries(path.searchParams);reply({items:[]})}
    else if(path.pathname.endsWith('/cut')) reply({cut:'5'})
    else if(path.pathname.endsWith('/ready')) {let body='';for await(const chunk of req) body+=chunk;readyBody=JSON.parse(body);reply({ready:true})}
    else reply(null,204)
  })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  const origin=`http://127.0.0.1:${server.address().port}`
  let session={userId:7,token:'old'}
  const {api}=createTsLoader({globals:{fetch,Headers,FormData,URLSearchParams,crypto:webcrypto,AbortController,setTimeout,clearTimeout,navigator:{userAgent:'MeshX test browser'}},
    replacements:{
      'apps/web/src/platform/nativeBridge.ts':{nativeBridge:{runtime:()=> 'web'}},
      'apps/web/src/platform/nativeTransport.ts':{nodeFetch:fetch},
      'apps/web/src/platform/nodeContext.ts':{currentNodeKey:()=>origin,currentNodeOrigin:()=>origin,currentNodeApiBasePath:()=>'/custom/api',apiUrl:path=>origin+'/custom/api'+path},
      'apps/web/src/utils/storage.ts':{readSession:()=>session,writeSession:value=>{session=value},clearSession:()=>{session=null}},
    }})('apps/web/src/services/api.ts')
  try {
    const input={protocolVersion:1,mode:'rebuild'}
    const opening=api.recovery.open(input,'same-logical-request');input.mode='mutated-after-call'
    assert.equal((await opening).recoveryId,'session-a')
    assert.equal(refreshes,1);assert.deepEqual(opens[0],opens[1]);assert.equal(opens.length,2)
    assert.equal(JSON.parse(opens[0].body).mode,'rebuild')
    await api.recovery.snapshot('session-a','opaque+token',200)
    assert.deepEqual(snapshotQuery,{pageToken:'opaque+token',limit:'200'})
    assert.equal((await api.recovery.cut('session-a')).cut,'5')
    await api.recovery.ready('session-a','9007199254740995',true)
    assert.deepEqual(readyBody,{appliedCursor:'9007199254740995',snapshotComplete:true})
    await assert.rejects(api.recovery.mutations('session-a','9007199254740992','9007199254740995',200),error=>error.code===409&&error.data.reason==='CURSOR_EXPIRED')
    assert.deepEqual(query,{after:'9007199254740992',through:'9007199254740995',limit:'200'})
    assert.equal(await api.recovery.release('session-a'),undefined)
    await assert.rejects(api.recovery.capabilities())
    assert.equal(paths.every(path=>path.startsWith('/custom/api/')),true)
  } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
})
