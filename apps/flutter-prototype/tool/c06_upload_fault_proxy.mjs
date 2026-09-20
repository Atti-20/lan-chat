// Test-only, loopback proxy: fail one upload, then relay the real fixture.
// Never logs headers, credentials, request bodies, or WebSocket payloads.
import http from 'node:http'
import { appendFileSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

export const failureMessage = '文件上传暂时不可用，请稍后重试'

export function createUploadFaultProxy(upstream, record = () => {}, mode = 'reject') {
  if (!['reject', 'stall'].includes(mode)) throw new Error('Unknown fault mode')
  const target = new URL(upstream)
  if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1'
      || target.username || target.password || target.pathname !== '/'
      || target.search || target.hash) {
    throw new Error('Only a loopback HTTP fixture origin is allowed')
  }
  let pendingFailure = true
  const sockets = new Set()
  const server = http.createServer((req, res) => {
    if (!req.url.startsWith('/') || req.url.startsWith('//')) {
      res.writeHead(400).end()
      return
    }
    const upload = req.method === 'POST' && req.url === '/api/v1/file/upload'
    if (upload && pendingFailure) {
      pendingFailure = false
      let bytes = 0
      req.on('data', chunk => { bytes += chunk.length })
      req.on('end', () => {
        if (mode === 'stall') {
          record({ event: 'upload-stalled', bytes, forwarded: false })
          res.on('close', () => record({ event: 'stalled-upload-closed' }))
          return
        }
        record({ event: 'upload-rejected', status: 503, bytes, forwarded: false })
        res.writeHead(503, { 'content-type': 'application/json' })
          .end(JSON.stringify({ code: 503, msg: failureMessage, data: null }))
      })
      return
    }
    const remote = http.request(target, {
      method: req.method, path: req.url,
      headers: { ...req.headers, host: target.host },
    }, response => {
      if (upload) record({ event: 'upload-relayed', status: response.statusCode })
      res.writeHead(response.statusCode, response.headers)
      response.pipe(res)
    })
    remote.on('error', () => {
      record({ event: 'upstream-error' })
      if (!res.headersSent) res.writeHead(502)
      res.end()
    })
    req.on('aborted', () => remote.destroy())
    res.on('close', () => remote.destroy())
    req.pipe(remote)
  })
  server.on('connection', socket => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })
  server.on('upgrade', (req, socket, head) => {
    const remote = http.request(target, {
      method: 'GET', path: req.url, agent: false,
      headers: { ...req.headers, host: target.host },
    })
    remote.on('upgrade', (response, peer, peerHead) => {
      sockets.add(peer)
      peer.on('close', () => sockets.delete(peer))
      socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n`)
      for (let i = 0; i < response.rawHeaders.length; i += 2) {
        socket.write(`${response.rawHeaders[i]}: ${response.rawHeaders[i + 1]}\r\n`)
      }
      socket.write('\r\n')
      if (peerHead.length) socket.write(peerHead)
      if (head.length) peer.write(head)
      peer.on('error', () => socket.destroy())
      socket.on('error', () => peer.destroy())
      socket.on('close', () => peer.destroy())
      peer.on('close', () => socket.destroy())
      peer.pipe(socket).pipe(peer)
    })
    remote.on('response', response => { response.resume(); socket.destroy() })
    remote.on('error', () => socket.destroy())
    remote.end()
  })
  return {
    server,
    close: () => { for (const socket of sockets) socket.destroy(); server.close() },
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [upstream, portText, evidence, mode = 'reject'] = process.argv.slice(2)
  if (!upstream || !evidence || !/^\d+$/.test(portText ?? '')
      || Number(portText) < 1024 || Number(portText) > 65535) {
    throw new Error('Usage: node c06_upload_fault_proxy.mjs http://127.0.0.1:PORT LISTEN_PORT EVIDENCE.jsonl [reject|stall]')
  }
  const proxy = createUploadFaultProxy(upstream, event => {
    appendFileSync(evidence, JSON.stringify({ at: new Date().toISOString(), ...event }) + '\n')
  }, mode)
  const info = await fetch(new URL('/api/v1/node/info', upstream), {
    signal: AbortSignal.timeout(5000), redirect: 'error',
  }).then(response => response.json())
  if (info.code !== 200 || info.data?.nodeId !== 'flutter-probe') {
    throw new Error('Refusing a node other than the dedicated Flutter fixture')
  }
  mkdirSync(dirname(evidence), { recursive: true })
  proxy.server.listen(Number(portText), '127.0.0.1', () => console.log('C06 fixture proxy ready'))
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => proxy.close())
}
