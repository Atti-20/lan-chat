import { SOCKET_CONNECTING, SOCKET_OPEN, SOCKET_CLOSING, SOCKET_CLOSED, type RealtimePort, type RealtimeSocket } from '../../../../../packages/platform-ports/src/realtime'
import { currentNodeOrigin, webSocketUrl } from '../nodeContext'

type NativeSocketEvent =
  | { type: 'open' }
  | { type: 'message'; data: string }
  | { type: 'error'; message: string }
  | { type: 'close'; code?: number | null; reason: string }


export const tauriRealtime: RealtimePort = { open: () => NativeRealtimeSocket.open() }

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
