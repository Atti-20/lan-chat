import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import {createTsLoader} from './helpers/load-ts.mjs'
const core=createTsLoader()('packages/domain-ts/src/recoverySnapshot.ts')
const f=JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/recovery-snapshot-pages.json',import.meta.url)))
const begin=()=>core.beginFullRecoverySnapshot(f.context,'snapshot-a','0')
const page=(items,next=null)=>({snapshotId:'snapshot-a',boundary:'0',items,nextPageToken:next,snapshotComplete:next===null})
test('full snapshot binds pages, completes once and ignores old callbacks',()=>{
  const start=begin(),first=core.stageRecoverySnapshotPage(start,f.context,null,page([f.directory],'next'),1)
  assert.equal(first.stage.complete,false);assert.equal(start.state.access.size,0)
  const last=core.stageRecoverySnapshotPage(first.stage,f.context,'next',page([f.message]),1)
  assert.equal(last.stage.complete,true);assert.equal(last.stage.state.messages.size,1);assert.equal(last.items[0].details.contentType,'image')
  assert.equal(core.stageRecoverySnapshotPage(last.stage,f.context,null,page([f.directory],'next'),1).ignored,true)
  assert.throws(()=>core.stageRecoverySnapshotPage(last.stage,f.context,'unknown',page([])))
})
test('invalid later item discards whole page; changed snapshot and cycling token fail',()=>{
  const first=core.stageRecoverySnapshotPage(begin(),f.context,null,page([f.directory],'next'),1).stage
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',page([f.message,{...f.message,messageId:'b',messageSequence:'3'}])))
  assert.equal(first.state.messages.size,0)
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',page([])))
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',{...page([f.message]),boundary:'1'}))
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',page([f.message],'next'),1))
})
test('revocation removes staged normal bodies from returned items',()=>{
  const result=core.stageRecoverySnapshotPage(begin(),f.context,null,page([f.directory,f.message,f.revoked]))
  assert.equal(result.stage.state.access.get('group:21').readAllowed,false)
  assert.equal(result.items.some(item=>item.kind==='MESSAGE'),false)
})
test('terminal proof cannot regress; same ID cannot move to another sequence',()=>{
  const terminal={...f.message,state:'RECALLED',objectVersion:'2',content:null,details:null}
  const first=core.stageRecoverySnapshotPage(begin(),f.context,null,page([f.directory,terminal],'next'),2).stage
  const last=core.stageRecoverySnapshotPage(first,f.context,'next',page([f.message]))
  assert.equal(last.stage.state.messages.get('message-a').state,'RECALLED');assert.equal(last.items.length,0)
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',page([{...terminal,state:'BURNED'}])))
  assert.throws(()=>core.stageRecoverySnapshotPage(first,f.context,'next',page([{...terminal,messageSequence:'2'}])))
})
test('normal rendering metadata is mandatory, terminal bodies forbidden, empty snapshot valid',()=>{
  for(const details of [{...f.message.details,fromUserId:0},{...f.message.details,createTime:'2026-02-30T00:00:00'},{...f.message.details,isBurn:true}]) {
    assert.throws(()=>core.parseSnapshotItem({...f.message,details}))
  }
  assert.throws(()=>core.parseSnapshotItem({...f.message,state:'BURNED'}))
  assert.equal(core.stageRecoverySnapshotPage(begin(),f.context,null,page([])).stage.complete,true)
})
