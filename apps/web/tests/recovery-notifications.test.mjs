import assert from 'node:assert/strict'
import test from 'node:test'
import {createTsLoader,plain} from './helpers/load-ts.mjs'
const {RecoveryNotificationJournal}=createTsLoader()('packages/domain-ts/src/notificationRecovery.ts')
const epoch='11111111-1111-4111-8111-111111111111'
const state=(terminal=false,read=true)=>({context:{origin:'http://node',userId:'7',streamEpoch:epoch,generation:1},phase:'ONLINE_SAFE',cursor:'1',messages:new Map([['m',{conversationId:'group:21',objectVersion:terminal?'2':'1',state:terminal?'RECALLED':'NORMAL'}]]),access:new Map([['group:21',{accessVersion:read?'1':'2',readAllowed:read,sendAllowed:read}]]),seen:new Map(),rebuild:new Set()})
test('snapshot reconciliation is silent; only explicit live candidates create durable SHOW',()=>{
 const journal=new RecoveryNotificationJournal();journal.reconcile(state());assert.deepEqual(plain(journal.image().effects),[{key:'migration',kind:'CANCEL_ALL'}]);
 journal.acknowledge('migration');journal.reconcile(state(),['m']);assert.equal(journal.image().effects[0].kind,'SHOW');assert.equal(journal.allows(state(),'m'),true);
 const restarted=new RecoveryNotificationJournal(plain(journal.image()));restarted.reconcile(state(),['m']);assert.equal(restarted.image().effects.length,1);
 restarted.acknowledge('show:m');restarted.reconcile(state());assert.equal(restarted.image().effects.length,0);assert.equal(restarted.allows(state(),'m'),true);
})
test('terminal or revoked proof invalidates route and supersedes queued SHOW with durable CANCEL',()=>{
 for(const changed of [state(true),state(false,false)]) {
  const journal=new RecoveryNotificationJournal();journal.acknowledge('migration');journal.reconcile(state(),['m']);journal.reconcile(changed);
  assert.equal(journal.allows(changed,'m'),false);assert.deepEqual(plain(journal.image().effects),[{key:'cancel:m',kind:'CANCEL',messageId:'m'}]);
  const retry=new RecoveryNotificationJournal(plain(journal.image()));retry.reconcile(changed);assert.equal(retry.image().effects.length,1);
  retry.acknowledge('cancel:m');assert.equal(retry.image().effects.length,0);
 }
})
test('unknown or body-bearing journal fields fail closed',()=>{
 assert.throws(()=>new RecoveryNotificationJournal({version:1,routes:[],effects:[],body:'secret'}),/UNSUPPORTED_SECURE_STATE/);
 assert.throws(()=>new RecoveryNotificationJournal({version:1,routes:[],effects:[{key:'show:m',kind:'SHOW',messageId:'m'}]}),/UNSUPPORTED_SECURE_STATE/);
})
