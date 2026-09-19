import assert from 'node:assert/strict'
import http from 'node:http'
import { once } from 'node:events'
import test from 'node:test'
import { createUploadFaultProxy, failureMessage } from './c06_upload_fault_proxy.mjs'

test('reject nonlocal targets before making requests', () => {
  for (const target of ['https://127.0.0.1', 'http://example.com', 'http://user:pass@127.0.0.1', 'http://127.0.0.1/path']) {
    assert.throws(() => createUploadFaultProxy(target), /loopback/)
  }
})

test('one upload fails without reaching upstream; retry preserves bytes and auth', async t => {
  const received = [], events = []
  const upstream = http.createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    received.push({ path: req.url, auth: req.headers.authorization, body: Buffer.concat(chunks).toString() })
    res.setHeader('set-cookie', 'fixture=example; HttpOnly')
    res.end('real fixture response')
  }).listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  t.after(() => { upstream.closeAllConnections(); upstream.close() })
  const proxy = createUploadFaultProxy(`http://127.0.0.1:${upstream.address().port}`, event => events.push(event))
  proxy.server.listen(0, '127.0.0.1')
  await once(proxy.server, 'listening')
  t.after(proxy.close)
  const origin = `http://127.0.0.1:${proxy.server.address().port}`
  assert.equal(await (await fetch(origin + '/api/v1/node/info')).text(), 'real fixture response')
  const request = { method: 'POST', body: 'actual multipart bytes', headers: { authorization: 'Bearer synthetic-only' } }
  const failed = await fetch(origin + '/api/v1/file/upload', request)
  assert.equal(failed.status, 503)
  assert.equal((await failed.json()).msg, failureMessage)
  assert.equal(received.length, 1)
  const retried = await fetch(origin + '/api/v1/file/upload', request)
  assert.equal(await retried.text(), 'real fixture response')
  assert.equal(retried.headers.get('set-cookie'), 'fixture=example; HttpOnly')
  assert.deepEqual(received[1], { path: '/api/v1/file/upload', auth: 'Bearer synthetic-only', body: request.body })
  assert.deepEqual(events.map(event => event.event), ['upload-rejected', 'upload-relayed'])
  assert.equal(events[0].forwarded, false)
  assert.ok(!JSON.stringify(events).includes('synthetic-only'))
})

test('upgrade relays bidirectional bytes without decoding or logging them', async t => {
  const upstream = http.createServer().listen(0, '127.0.0.1')
  upstream.on('upgrade', (req, socket) => {
    assert.equal(req.url, '/ws/chat')
    socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n')
    socket.on('data', bytes => socket.write(bytes))
  })
  await once(upstream, 'listening')
  t.after(() => upstream.close())
  const proxy = createUploadFaultProxy(`http://127.0.0.1:${upstream.address().port}`)
  proxy.server.listen(0, '127.0.0.1')
  await once(proxy.server, 'listening')
  t.after(proxy.close)
  const req = http.request(`http://127.0.0.1:${proxy.server.address().port}/ws/chat`, {
    headers: { Connection: 'Upgrade', Upgrade: 'websocket' },
  })
  req.end()
  const [, socket] = await once(req, 'upgrade')
  t.after(() => socket.destroy())
  const reply = once(socket, 'data')
  socket.write('opaque fixture bytes')
  assert.equal((await reply)[0].toString(), 'opaque fixture bytes')
})

test('stalled upload is not forwarded; client cancellation closes it and retry succeeds', async t => {
  let upstreamRequests = 0
  const events = []
  let markStalled, markClosed
  const stalled = new Promise(resolve => { markStalled = resolve })
  const closed = new Promise(resolve => { markClosed = resolve })
  const upstream = http.createServer(async (req, res) => {
    for await (const chunk of req) { void chunk }
    upstreamRequests++
    res.end('retry succeeded')
  }).listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  t.after(() => { upstream.closeAllConnections(); upstream.close() })
  const proxy = createUploadFaultProxy(`http://127.0.0.1:${upstream.address().port}`, event => {
    events.push(event)
    if (event.event === 'upload-stalled') markStalled()
    if (event.event === 'stalled-upload-closed') markClosed()
  }, 'stall')
  proxy.server.listen(0, '127.0.0.1')
  await once(proxy.server, 'listening')
  t.after(proxy.close)
  const url = `http://127.0.0.1:${proxy.server.address().port}/api/v1/file/upload`
  const abort = new AbortController()
  const request = fetch(url, { method: 'POST', body: 'cancel this', signal: abort.signal })
  const rejected = assert.rejects(request, { name: 'AbortError' })
  await stalled
  assert.equal(upstreamRequests, 0)
  abort.abort()
  await rejected
  await closed
  assert.equal(await (await fetch(url, { method: 'POST', body: 'new file' })).text(), 'retry succeeded')
  assert.equal(upstreamRequests, 1)
  assert.deepEqual(events.map(event => event.event), ['upload-stalled', 'stalled-upload-closed', 'upload-relayed'])
})
