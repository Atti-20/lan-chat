import { currentNodeOrigin, webSocketUrl } from './nodeContext'
import { nativeBridge } from './nativeBridge'

interface NativeHttpResponse {
  status: number
  headers: Record<string, string>
  bodyBase64: string
}

type NativeRequestBody =
  | { kind: 'text'; value: string }
  | { kind: 'binary'; base64: string }
  | { kind: 'multipart'; parts: NativeMultipartPart[] }

interface NativeMultipartPart {
  name: string
  value?: string
  base64?: string
  fileName?: string
  contentType?: string
}

type NativeSocketEvent =
  | { type: 'open' }
  | { type: 'message'; data: string }
  | { type: 'error'; message: string }
  | { type: 'close'; code?: number | null; reason: string }

export interface RealtimeSocket {
  readonly readyState: number
  onopen: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onerror: (() => void) | null
  onclose: (() => void) | null
  send(data: string): void
  close(): void
}

export const SOCKET_CONNECTING = 0
export const SOCKET_OPEN = 1
export const SOCKET_CLOSING = 2
export const SOCKET_CLOSED = 3

export async function nodeFetch(
  input: string | URL,
  init: RequestInit = {},
): Promise<Response> {
  if (nativeBridge.runtime() !== 'tauri') return fetch(input, init)
  const origin = currentNodeOrigin()
  const url = new URL(input, origin)
  if (url.origin !== origin) throw new Error('请求地址离开了所选节点')
  if (init.signal?.aborted) throw abortError()

  const { invoke } = await import('@tauri-apps/api/core')
  const requestId = `request_${crypto.randomUUID().replace(/-/g, '')}`
  const cancel = () => {
    void invoke('cancel_node_request', { requestId }).catch(() => undefined)
  }
  init.signal?.addEventListener('abort', cancel, { once: true })
  try {
    const response = await invoke<NativeHttpResponse>('node_http_request', {
      request: {
        requestId,
        origin,
        method: init.method || 'GET',
        path: `${url.pathname}${url.search}`,
        headers: Object.fromEntries(new Headers(init.headers).entries()),
        body: await serializeBody(init.body),
      },
    })
    if (init.signal?.aborted) throw abortError()
    return new Response(decodeBase64(response.bodyBase64), {
      status: response.status,
      headers: response.headers,
    })
  } catch (cause) {
    if (init.signal?.aborted || String(cause).includes('NODE_REQUEST_CANCELLED')) {
      throw abortError()
    }
    throw cause
  } finally {
    init.signal?.removeEventListener('abort', cancel)
  }
}

export async function openRealtimeSocket(): Promise<RealtimeSocket> {
  if (nativeBridge.runtime() !== 'tauri') {
    return new WebSocket(webSocketUrl()) as unknown as RealtimeSocket
  }
  return NativeRealtimeSocket.open()
}

class NativeRealtimeSocket implements RealtimeSocket {
  readyState = SOCKET_CONNECTING
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null

  private closeRequested = false
  private openDelivered = false
  private closeDelivered = false
  private openListener: (() => void) | null = null
  private closeListener: (() => void) | null = null

  private constructor(private readonly socketId: string) {}

  get onopen(): (() => void) | null {
    return this.openListener
  }

  set onopen(listener: (() => void) | null) {
    this.openListener = listener
    if (listener && this.readyState === SOCKET_OPEN && !this.openDelivered) {
      this.openDelivered = true
      queueMicrotask(listener)
    }
  }

  get onclose(): (() => void) | null {
    return this.closeListener
  }

  set onclose(listener: (() => void) | null) {
    this.closeListener = listener
    if (listener && this.readyState === SOCKET_CLOSED && !this.closeDelivered) {
      this.closeDelivered = true
      queueMicrotask(listener)
    }
  }

  static async open(): Promise<NativeRealtimeSocket> {
    const socket = new NativeRealtimeSocket(
      `socket_${crypto.randomUUID().replace(/-/g, '')}`,
    )
    const target = new URL(webSocketUrl())
    const { Channel, invoke } = await import('@tauri-apps/api/core')
    const events = new Channel<NativeSocketEvent>((event) => socket.handle(event))
    try {
      await invoke('open_node_socket', {
        request: {
          socketId: socket.socketId,
          origin: currentNodeOrigin(),
          path: target.pathname,
        },
        onEvent: events,
      })
      if (socket.closeRequested) socket.close()
    } catch {
      socket.readyState = SOCKET_CLOSED
      queueMicrotask(() => {
        socket.onerror?.()
        socket.deliverClose()
      })
    }
    return socket
  }

  send(data: string): void {
    if (this.readyState !== SOCKET_OPEN) throw new Error('WebSocket 尚未连接')
    void import('@tauri-apps/api/core')
      .then(({ invoke }) => invoke('send_node_socket', { socketId: this.socketId, data }))
      .catch(() => this.onerror?.())
  }

  close(): void {
    if (this.readyState === SOCKET_CLOSED || this.readyState === SOCKET_CLOSING) return
    this.closeRequested = true
    this.readyState = SOCKET_CLOSING
    void import('@tauri-apps/api/core')
      .then(({ invoke }) => invoke('close_node_socket', { socketId: this.socketId }))
      .catch(() => undefined)
  }

  private handle(event: NativeSocketEvent): void {
    if (event.type === 'open') {
      if (this.closeRequested) {
        this.close()
        return
      }
      this.readyState = SOCKET_OPEN
      if (this.openListener && !this.openDelivered) {
        this.openDelivered = true
        this.openListener()
      }
      return
    }
    if (event.type === 'message') {
      if (this.readyState === SOCKET_OPEN) this.onmessage?.({ data: event.data })
      return
    }
    if (event.type === 'error') {
      this.onerror?.()
      return
    }
    this.readyState = SOCKET_CLOSED
    this.deliverClose()
  }

  private deliverClose(): void {
    if (!this.closeListener || this.closeDelivered) return
    this.closeDelivered = true
    this.closeListener()
  }
}

async function serializeBody(body: BodyInit | null | undefined): Promise<NativeRequestBody | null> {
  if (body === undefined || body === null) return null
  if (typeof body === 'string') return { kind: 'text', value: body }
  if (body instanceof URLSearchParams) return { kind: 'text', value: body.toString() }
  if (body instanceof FormData) {
    const parts: NativeMultipartPart[] = []
    for (const [name, value] of body.entries()) {
      if (typeof value === 'string') {
        parts.push({ name, value })
      } else {
        parts.push({
          name,
          base64: await blobBase64(value),
          fileName: value.name,
          contentType: value.type || 'application/octet-stream',
        })
      }
    }
    return { kind: 'multipart', parts }
  }
  if (body instanceof Blob) return { kind: 'binary', base64: await blobBase64(body) }
  if (body instanceof ArrayBuffer) {
    return { kind: 'binary', base64: bytesBase64(new Uint8Array(body)) }
  }
  if (ArrayBuffer.isView(body)) {
    return {
      kind: 'binary',
      base64: bytesBase64(new Uint8Array(body.buffer, body.byteOffset, body.byteLength)),
    }
  }
  throw new Error('桌面原生网络层不支持该请求体类型')
}

function blobBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error || new Error('无法读取请求文件'))
    reader.onload = () => {
      const value = String(reader.result || '')
      const separator = value.indexOf(',')
      if (separator < 0) reject(new Error('无法编码请求文件'))
      else resolve(value.slice(separator + 1))
    }
    reader.readAsDataURL(blob)
  })
}

function bytesBase64(bytes: Uint8Array): string {
  const chunkSize = 32_768
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return btoa(binary)
}

function decodeBase64(value: string): ArrayBuffer {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes.buffer
}

function abortError(): DOMException {
  return new DOMException('请求已取消', 'AbortError')
}
