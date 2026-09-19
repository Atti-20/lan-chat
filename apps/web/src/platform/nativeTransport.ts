import type { RealtimePort, RealtimeSocket } from '../../../../packages/platform-ports/src/realtime'
export { SOCKET_CONNECTING, SOCKET_OPEN, SOCKET_CLOSING, SOCKET_CLOSED, type RealtimeSocket } from '../../../../packages/platform-ports/src/realtime'
import { webRealtime } from './web/realtime'
import { tauriRealtime } from './tauri/realtime'
import { currentNodeOrigin } from './nodeContext'
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

// Existing public facade remains valid; only the host-specific implementations move.
export const realtimeTransport: RealtimePort = {
  open: () => (nativeBridge.runtime() === 'tauri' ? tauriRealtime : webRealtime).open(),
}

export function openRealtimeSocket(): Promise<RealtimeSocket> {
  return realtimeTransport.open()
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
