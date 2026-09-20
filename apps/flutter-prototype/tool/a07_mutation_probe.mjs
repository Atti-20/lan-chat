// Characterize v1 against an OWNED backend_fixture.py instance; no production defaults.
// Saves synthetic message payloads only, never credentials. A reproduced gap is not a safety PASS.
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const out = process.env.PROBE_OUTPUT_DIR
assert.ok(out, 'PROBE_OUTPUT_DIR must identify an owned fixture')
const state = JSON.parse(await readFile(path.join(out, 'backend-state.json')))
assert.equal(state.mysql, 'meshx-a07b1-mysql')
const origin = `http://127.0.0.1:${state.port}`
async function api(method, route, body, token, allowFailure = false) {
  const response = await fetch(origin + '/api/v1' + route, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const result = await response.json()
  if (!allowFailure) assert.equal(result.code, 200, `${method} ${route}: ${result.msg}`)
  return allowFailure ? { http: response.status, ...result } : result.data
}
assert.equal((await api('GET', '/node/info')).nodeId, 'flutter-probe')
const suffix = randomBytes(4).toString('hex'), password = 'Ab9_' + randomBytes(12).toString('base64url')
const users = []
for (let i = 0; i < 3; i++) {
  const username = `mutation_${i}_${suffix}`
  await api('POST', '/auth/register', { username, password, nickname: `Mutation ${i}` })
  users.push(await api('POST', '/auth/login', { username, password, deviceType: 'web' }))
}
const [owner, reader, peer] = users
for (const friend of [reader, peer]) {
  await api('POST', '/friend/request', { toUserId: friend.userId }, owner.token)
  const request = (await api('GET', '/friend/requests', null, friend.token)).find(r => r.fromUserId === owner.userId)
  await api('POST', '/friend/handle', { requestId: request.id, accept: true }, friend.token)
}
const group = await api('POST', '/group', { groupName: 'Mutation fixture', memberIds: [reader.userId, peer.userId] }, owner.token)
const cid = `group:${group.id}`
const sockets = []
async function connect(user) {
  const ws = new WebSocket(origin.replace('http', 'ws') + '/ws/chat'), pending = new Map(), events = []
  sockets.push(ws)
  ws.addEventListener('message', e => {
    const frame = JSON.parse(e.data); events.push(frame)
    const entry = pending.get(frame.requestId)
    if (entry && (entry.event === frame.event || frame.event === 'ERROR')) {
      clearTimeout(entry.timer); pending.delete(frame.requestId); entry.resolve(frame)
    }
  })
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  const request = (event, payload, responseEvent, extra = {}) => new Promise((resolve, reject) => {
    const requestId = randomBytes(12).toString('hex')
    const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`Timeout ${event}`)) }, 10000)
    pending.set(requestId, { event: responseEvent, resolve, timer })
    ws.send(JSON.stringify({ version: 1, event, requestId, timestamp: Date.now(), payload, ...extra }))
  })
  assert.equal((await request('AUTH', { token: user.token }, 'AUTH_OK')).event, 'AUTH_OK')
  return { ws, events, request, close: () => new Promise(resolve => { ws.addEventListener('close', resolve, { once: true }); ws.close() }) }
}
const report = { origin, scenarios: {}, captures: {} }
try {
  const sender = await connect(owner)
  let client = await connect(reader)
  const send = (content, burn = false, conversationId = cid, toUserId) => sender.request('CHAT_SEND', {
    ...(toUserId ? { toUserId } : { groupId: group.id }), contentType: 'text', content, isBurn: burn,
  }, 'CHAT_ACK', { conversationId, clientMsgId: randomBytes(12).toString('hex') })
  const sync = (socket, conversationId, after) => socket.request('SYNC_REQUEST', { positions: { [conversationId]: after }, limit: 200 }, 'SYNC_RESPONSE')
  const recalled = await send('synthetic recalled text'), burned = await send('synthetic burned text', true)
  for (let i = 0; i < 60; i++) await send(`synthetic later ${i}`)
  const initial = await sync(client, cid, 0), tail = initial.payload.latestPositions[cid]
  assert.equal(initial.payload.messages.length, 62)
  // The disconnected recipient has a real server snapshot, persisted separately below.
  report.captures.cached = initial
  await client.close()
  const recallEvent = await sender.request('CHAT_RECALL', { messageId: recalled.payload.messageId }, 'CHAT_RECALL')
  const burnEvent = await sender.request('CHAT_BURN', { messageId: burned.payload.messageId }, 'CHAT_BURN')
  assert.equal(recallEvent.event, 'CHAT_RECALL'); assert.equal(burnEvent.event, 'CHAT_BURN')
  // A different session updates the offline reader's private read cursor via REST.
  await api('PUT', `/chat/conversation/read?conversationId=${encodeURIComponent(cid)}&lastReadSequence=${tail}`, null, reader.token)
  client = await connect(reader)
  const resumed = await sync(client, cid, tail)
  const authoritative = await sync(client, cid, 0)
  const historyTail = await api('GET', `/chat/history?conversationId=${encodeURIComponent(cid)}&limit=50`, null, reader.token)
  const a = authoritative.payload.messages.find(m => m.messageId === recalled.payload.messageId)
  const b = authoritative.payload.messages.find(m => m.messageId === burned.payload.messageId)
  assert.equal(a.isRecalled, 1); assert.equal(a.content, '')
  assert.equal(b.status, 2); assert.equal(b.content, '')
  assert.equal(resumed.payload.messages.length, 0); assert.equal(resumed.payload.latestPositions[cid], tail)
  assert.equal(historyTail.some(m => m.messageId === a.messageId || m.messageId === b.messageId), false)
  report.captures = { ...report.captures, resumed, authoritative, historyTail, recallEvent, burnEvent }
  report.scenarios.recall = { safety: 'FAIL', classification: 'PROTOCOL_GAP', originalSequence: a.sequence, eventSequence: recallEvent.payload.sequence, tail, resumedCount: 0, fullReplayShowsRecall: true }
  report.scenarios.burn = { safety: 'FAIL', classification: 'PROTOCOL_GAP', originalSequence: b.sequence, eventSequence: burnEvent.payload.sequence, tail, resumedCount: 0, fullReplayShowsBurn: true }
  const summary = (await api('GET', '/chat/conversations', null, reader.token)).find(c => c.conversationId === cid)
  report.captures.readSummary = summary
  assert.equal(summary.lastReadSequence, tail)
  report.scenarios.read = { recovery: 'PASS', source: 'GET chat/conversations', lastReadSequence: summary.lastReadSequence, peerReadProof: false }
  await client.close()
  await api('DELETE', `/group/${group.id}/members/${reader.userId}`, null, owner.token)
  client = await connect(reader)
  const denied = await sync(client, cid, tail)
  const deniedHistory = await api('GET', `/chat/history?conversationId=${encodeURIComponent(cid)}`, null, reader.token, true)
  const deniedSend = await client.request('CHAT_SEND', { groupId: group.id, contentType: 'text', content: 'synthetic queued text', isBurn: false }, 'CHAT_ACK', { conversationId: cid, clientMsgId: randomBytes(12).toString('hex') })
  assert.ok(denied.payload.deniedConversationIds.includes(cid)); assert.notEqual(deniedHistory.code, 200); assert.equal(deniedSend.event, 'ERROR')
  report.captures.denied = denied
  report.scenarios.groupRemoved = { server: 'PASS', syncDenied: true, historyCode: deniedHistory.code, sendEvent: deniedSend.event, summariesContainRemoved: (await api('GET', '/chat/conversations', null, reader.token)).some(c => c.conversationId === cid) }
  const privateId = `private:${Math.min(owner.userId, reader.userId)}:${Math.max(owner.userId, reader.userId)}`
  await send('synthetic private text', false, privateId, reader.userId)
  const privateCache = await sync(client, privateId, 0)
  await client.close()
  await api('DELETE', `/friend/${reader.userId}`, null, owner.token)
  client = await connect(reader)
  const friendSync = await sync(client, privateId, privateCache.payload.latestPositions[privateId])
  const friendHistory = await api('GET', `/chat/history?conversationId=${encodeURIComponent(privateId)}`, null, reader.token, true)
  const friendSend = await client.request('CHAT_SEND', { toUserId: owner.userId, contentType: 'text', content: 'synthetic revoked queue', isBurn: false }, 'CHAT_ACK', { conversationId: privateId, clientMsgId: randomBytes(12).toString('hex') })
  report.captures.friendSync = friendSync
  report.scenarios.friendRemoved = { syncDenied: friendSync.payload.deniedConversationIds.includes(privateId), historyCode: friendHistory.code, sendEvent: friendSend.event }
  await writeFile(path.join(out, 'mutation-characterization.json'), JSON.stringify(report, null, 2) + '\n')
  // Short-lived synthetic fixture tokens, kept outside source/contract evidence.
  await writeFile(path.join(out, 'live-client-config.json'), JSON.stringify({ origin, owner, reader: peer, groupId: group.id, conversationId: cid }), { mode: 0o600 })
  console.log(JSON.stringify(report.scenarios, null, 2))
} finally { for (const ws of sockets) if (ws.readyState < 2) ws.close() }
