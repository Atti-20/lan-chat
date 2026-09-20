import assert from 'node:assert/strict'
import test from 'node:test'
import {createTsLoader,plain} from './helpers/load-ts.mjs'
const {recoveredConversationSummaries:summarize}=createTsLoader()('packages/domain-ts/src/recoveredConversationSummary.ts')
const row={conversationId:'group:21',messageId:'peer',sequence:1,state:'NORMAL',fromUserId:8,content:'verified',contentType:'text',timestamp:'2026-09-20T00:00:00'}
test('verified summaries count peer history after own read position and use sequence ordering',()=>{
 const result=summarize([{...row,sequence:3,messageId:'own',fromUserId:7,content:'own'},row,{...row,messageId:'peer2',sequence:2}],7,new Map([['group:21',1]])).get('group:21')
 assert.deepEqual(plain(result),{preview:'own',unreadCount:1,lastSequence:3,timestamp:row.timestamp})
})
test('terminal latest summary has no stale body or fabricated timestamp',()=>{
 for(const [state,label] of [['RECALLED','这条消息已撤回'],['BURNED','这条消息已焚毁'],['UNAVAILABLE','这条消息已不可用']]) {
  const result=summarize([row,{conversationId:row.conversationId,messageId:'terminal',sequence:2,state}],7,new Map([['group:21',0]])).get('group:21')
  assert.deepEqual(plain(result),{preview:label,unreadCount:1,lastSequence:2})
 }
})
test('burn bodies and unsupported media never become list previews; missing baseline does not invent unread',()=>{
 for(const [patch,label] of [[{isBurn:true},'阅后即焚消息'],[{contentType:'image'},'图片'],[{contentType:'unknown'},'暂不预览此类消息']]) {
  const result=summarize([{...row,...patch}],7,new Map()).get('group:21')
  assert.equal(result.preview,label);assert.equal(result.unreadCount,0)
 }
 assert.equal(summarize([],7,new Map()).size,0)
})
