// Seed only the dedicated local fixture, using real registration, friendship and WS APIs.
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const out = process.env.PROBE_OUTPUT_DIR || path.join(root, 'output/flutter-prototype-2026-09-08')
const origin = 'http://127.0.0.1:' + (process.env.PROBE_HTTP_PORT || '18381')
const suffix = randomBytes(4).toString('hex')
const password = 'Ab9_' + randomBytes(12).toString('base64url')
const count = Number(process.env.PROBE_MESSAGE_COUNT || 1200)
const smallCount = Number(process.env.PROBE_SMALL_MESSAGE_COUNT || 0)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function api(method, route, body, token) {
  const response = await fetch(origin + '/api/v1' + route, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const json = await response.json()
  if (!response.ok || json.code !== 200) throw new Error(route + ': ' + json.msg)
  return json.data
}
const node = await api('GET', '/node/info')
if (node.nodeId !== 'flutter-probe') throw new Error('Refusing to seed a node other than the dedicated fixture')
await readFile(path.join(out, 'backend-state.json')) // Explicit ownership evidence from backend_fixture.py.
const users = []
for (const [index, nickname] of ['林舟', '许澄', '周予'].entries()) {
  const username = 'probe_' + index + '_' + suffix
  await api('POST', '/auth/register', { username, password, nickname })
  const session = await api('POST', '/auth/login', { username, password, deviceType: 'web', deviceName: 'fixture seeder' })
  users.push({ ...session, username })
}
for (const peer of users.slice(1)) {
  await api('POST', '/friend/request', { toUserId: peer.userId, message: '独立原型验证' }, users[0].token)
  const pending = await api('GET', '/friend/requests', null, peer.token)
  const request = pending.find(item => Number(item.fromUserId) === Number(users[0].userId))
  if (!request) throw new Error('Fixture friend request missing')
  await api('POST', '/friend/handle', { requestId: request.id, accept: true }, peer.token)
}
const group = await api('POST', '/group', {
  groupName: 'MeshX 产品讨论', memberIds: users.slice(1).map(user => user.userId),
  announcement: '独立验证空间：列表、输入和原生节点发现。',
}, users[0].token)
const smallGroup = smallCount > 0 ? await api('POST', '/group', {
  groupName: 'MeshX 220 条性能样本', memberIds: users.slice(1).map(user => user.userId),
  announcement: '独立验收空间：与大列表分离的稳定数据集。',
}, users[0].token) : null

async function connect(user) {
  const ws = new WebSocket(origin.replace('http', 'ws') + '/ws/chat')
  const pending = new Map()
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    const key = message.clientMsgId || message.requestId
    if (pending.has(key)) {
      const [resolve, reject, timer] = pending.get(key)
      if (['AUTH_OK', 'CHAT_ACK', 'ERROR'].includes(message.event)) {
        clearTimeout(timer); pending.delete(key)
        if (message.event === 'ERROR') reject(new Error(message.payload.message || 'WS error'))
        else resolve(message)
      }
    }
  })
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })
  const send = (event, payload, extras = {}) => new Promise((resolve, reject) => {
    const id = randomBytes(16).toString('hex')
    const envelope = { version: 1, event, requestId: id, timestamp: Date.now(), payload, ...extras }
    const key = envelope.clientMsgId || id
    const timer = setTimeout(() => { pending.delete(key); reject(new Error('Timed out waiting for ' + event)) }, 15000)
    pending.set(key, [resolve, reject, timer]); ws.send(JSON.stringify(envelope))
  })
  await send('AUTH', { token: user.token })
  return { ws, send }
}
const clients = await Promise.all(users.map(connect))
const examples = [
  '今天先把消息列表和输入体验串起来，原生能力用局域网发现验证。',
  '节点地址会先做握手检查，再允许登录。扫描到的地址不会直接当成可信服务器。',
  '收到。浅色和深色都看一下，长消息要自然换行，键盘弹出后发送按钮也要保持可见。',
  '这一条用于检查连续文本：MeshX / Android / iOS。中英文混排、标点，以及 https://example.com/docs 都应该保持可读。',
  '断线后先补拉历史，再恢复发送。发送成功以服务端确认作为依据。',
  '可以，稍后一起检查后台切换和网络恢复。',
]
try {
  for (let index = 0; index < count; index++) {
    const sender = index % 3
    await clients[sender].send('CHAT_SEND', {
      groupId: group.id, contentType: 'text', content: examples[index % examples.length] + ' [' + (index + 1) + ']',
      isBurn: false,
    }, { conversationId: 'group:' + group.id, clientMsgId: randomBytes(16).toString('hex') })
    if (index % 100 === 99) { console.log('Seeded ' + (index + 1) + ' committed group messages'); await sleep(50) }
  }
  for (let index = 0; index < smallCount; index++) {
    const sender = index % 3
    await clients[sender].send('CHAT_SEND', {
      groupId: smallGroup.id, contentType: 'text', content: examples[index % examples.length] + ' [small-' + (index + 1) + ']',
      isBurn: false,
    }, { conversationId: 'group:' + smallGroup.id, clientMsgId: randomBytes(16).toString('hex') })
  }
  for (let peer = 1; peer < users.length; peer++) {
    for (let index = 0; index < 12; index++) {
      const sender = index % 2 ? 0 : peer, receiver = sender === 0 ? peer : 0
      const ids = [users[0].userId, users[peer].userId].sort((a,b) => a-b)
      await clients[sender].send('CHAT_SEND', {
        toUserId: users[receiver].userId, contentType: 'text', content: examples[index % examples.length], isBurn: false,
      }, { conversationId: 'private:' + ids.join(':'), clientMsgId: randomBytes(16).toString('hex') })
    }
  }
} finally { clients.forEach(client => client.ws.close()) }
await mkdir(out, { recursive: true })
const config = {
  MESHX_NODE: origin, PROBE_USERNAME: users[0].username, PROBE_PASSWORD: password,
  PROBE_PEER_USERNAME: users[1].username, PROBE_PEER_PASSWORD: password,
  PROBE_GROUP_ID: String(group.id), PROBE_USER_ID: String(users[0].userId),
  ...(smallGroup ? { PROBE_SMALL_GROUP_ID: String(smallGroup.id) } : {}),
}
await writeFile(path.join(out, 'integration-config.json'), JSON.stringify(config, null, 2), { mode: 0o600 })
await writeFile(path.join(out, 'integration-android-config.json'),
  JSON.stringify({ ...config, MESHX_NODE: origin.replace('127.0.0.1', '10.0.2.2') }, null, 2), { mode: 0o600 })
await writeFile(path.join(out, 'seed-summary.json'), JSON.stringify({
  node: node.nodeId, users: users.length, groupMessages: count,
  smallGroupMessages: smallCount, privateMessages: 24,
  groupId: group.id, createdAt: new Date().toISOString(),
  ...(smallGroup ? { smallGroupId: smallGroup.id } : {}),
}, null, 2))
console.log('Seed complete. Test credentials are in the ignored local integration config.')
