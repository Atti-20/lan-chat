import assert from 'node:assert/strict'
import test from 'node:test'
import { createTsLoader, plain } from './helpers/load-ts.mjs'

const flush = async () => { for (let i = 0; i < 3; i++) await new Promise(setImmediate) }

function harness(runtime, { earlyOpen = false, failOpen = false } = {}) {
  let channel, browserSocket
  const calls = []
  const node = { origin: 'http://node-a.test:8080', path: '/nested/ws/chat' }
  class BrowserSocket {
    readyState = 0
    onopen = null; onmessage = null; onerror = null; onclose = null
    constructor(url) { browserSocket = this; calls.push(['WebSocket', url]) }
    send(data) { calls.push(['browser-send', data]) }
    close() { this.readyState = 3; this.onclose?.() }
  }
  const load = createTsLoader({ globals: { WebSocket: BrowserSocket, URL, queueMicrotask,
    crypto: { randomUUID: () => 'a03-socket-id' } },
  replacements: {
    'apps/web/src/platform/nativeBridge.ts': { nativeBridge: { runtime: () => runtime } },
    'apps/web/src/platform/nodeContext.ts': { currentNodeOrigin: () => node.origin,
      webSocketUrl: () => node.origin.replace('http', 'ws') + node.path },
  }, externals: {
    '@tauri-apps/api/core': {
      Channel: class { constructor(callback) { channel = callback } },
      async invoke(command, args) {
        calls.push([command, plain(args)])
        if (command === 'open_node_socket') {
          if (failOpen) throw new Error('open failed')
          if (earlyOpen) channel({ type: 'open' })
        }
        if (command === 'close_node_socket') channel({ type: 'close', code: 1000, reason: 'closed' })
      },
    },
  } })
  const facade = load('apps/web/src/platform/nativeTransport.ts')
  return { calls, node, facade,
    emit(type, data) {
      if (runtime === 'tauri') channel({ type, data, reason: '', code: 1000 })
      else if (type === 'open') { browserSocket.readyState = 1; browserSocket.onopen?.() }
      else if (type === 'message') browserSocket.onmessage?.({ data })
      else if (type === 'error') browserSocket.onerror?.()
      else { browserSocket.readyState = 3; browserSocket.onclose?.() }
    },
  }
}

for (const runtime of ['web', 'tauri']) {
  test(`${runtime} RealtimePort preserves connect/frame/send/error/close contract through the public facade`, async () => {
    const h = harness(runtime)
    const socket = await h.facade.openRealtimeSocket()
    assert.equal(socket.readyState, h.facade.SOCKET_CONNECTING)
    const events = []
    socket.onopen = () => events.push('open')
    socket.onmessage = event => events.push(event.data)
    socket.onerror = () => events.push('error')
    socket.onclose = () => events.push('close')
    h.emit('open'); assert.equal(socket.readyState, h.facade.SOCKET_OPEN)
    h.emit('message', '{"event":"AUTH_OK"}')
    socket.send('{"event":"SYNC_REQUEST"}'); await flush()
    h.emit('error'); socket.close(); await flush()
    assert.deepEqual(events, ['open', '{"event":"AUTH_OK"}', 'error', 'close'])
    assert.equal(socket.readyState, h.facade.SOCKET_CLOSED)
    if (runtime === 'web') assert.deepEqual(h.calls[0], ['WebSocket', 'ws://node-a.test:8080/nested/ws/chat'])
    else {
      assert.equal(h.calls[0][0], 'open_node_socket')
      assert.deepEqual(h.calls[0][1].request, { socketId: 'socket_a03socketid', origin: 'http://node-a.test:8080', path: '/nested/ws/chat' })
      assert.deepEqual(h.calls.find(call => call[0] === 'send_node_socket')[1], { socketId: 'socket_a03socketid', data: '{"event":"SYNC_REQUEST"}' })
    }
  })
}

test('Tauri buffers an early native open event until the consumer installs its listener', async () => {
  const h = harness('tauri', { earlyOpen: true })
  const socket = await h.facade.realtimeTransport.open()
  let opened = 0
  socket.onopen = () => opened++
  await flush(); assert.equal(opened, 1)
  h.emit('open'); assert.equal(opened, 1)
  socket.close(); await flush()
})

test('Tauri open rejection closes the adapter and delivers a late-installed close listener once', async () => {
  const h = harness('tauri', { failOpen: true })
  const socket = await h.facade.realtimeTransport.open()
  let closed = 0
  socket.onclose = () => closed++
  await flush()
  assert.equal(socket.readyState, 3); assert.equal(closed, 1)
})

test('a new Web/Tauri connection uses the selected node at open time, without caching node A', async () => {
  for (const runtime of ['web', 'tauri']) {
    const h = harness(runtime)
    const old = await h.facade.realtimeTransport.open(); h.emit('open'); old.close(); await flush()
    h.node.origin = 'https://node-b.test'; h.node.path = '/other/ws'
    await h.facade.realtimeTransport.open()
    const opened = h.calls.filter(call => call[0] === (runtime === 'web' ? 'WebSocket' : 'open_node_socket'))
    assert.equal(opened.length, 2)
    if (runtime === 'web') assert.equal(opened[1][1], 'wss://node-b.test/other/ws')
    else assert.equal(opened[1][1].request.origin, 'https://node-b.test')
  }
})
