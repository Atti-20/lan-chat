import WebSocket from 'ws'

export interface WsEnvelope {
  version: number
  event: string
  requestId?: string
  clientMsgId?: string
  conversationId?: string
  timestamp: number
  payload: Record<string, unknown>
}

type Predicate = (envelope: WsEnvelope) => boolean

interface Waiter {
  predicate: Predicate
  resolve: (envelope: WsEnvelope) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

export class WsClient {
  private socket?: WebSocket
  private readonly received: WsEnvelope[] = []
  private readonly waiters = new Set<Waiter>()

  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
  ) {}

  async connect(): Promise<void> {
    const wsUrl = new URL('/ws/chat', this.baseUrl)
    wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new WebSocket(wsUrl, {
      origin: this.baseUrl,
    })
    this.socket = socket
    this.bindSocket(socket)

    await new Promise<void>((resolve, reject) => {
      socket.once('open', resolve)
      socket.once('error', reject)
    })

    const authenticated = this.waitFor(
      (envelope) => envelope.event === 'AUTH_OK',
    )
    this.send('AUTH', { token: this.token }, {
      requestId: this.id('auth'),
    })
    await authenticated
  }

  send(
    event: string,
    payload: Record<string, unknown>,
    metadata: Partial<Pick<
      WsEnvelope,
      'requestId' | 'clientMsgId' | 'conversationId'
    >> = {},
  ): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket is not open')
    }
    this.socket.send(JSON.stringify({
      version: 1,
      event,
      timestamp: Date.now(),
      payload,
      ...metadata,
    }))
  }

  waitFor(predicate: Predicate, timeoutMs = 15_000): Promise<WsEnvelope> {
    const existing = this.received.find(predicate)
    if (existing) return Promise.resolve(existing)

    return new Promise((resolve, reject) => {
      const waiter: Waiter = {
        predicate,
        resolve,
        reject,
        timer: setTimeout(() => {
          this.waiters.delete(waiter)
          reject(new Error(
            `Timed out waiting for WebSocket event; received: ${
              this.received.map((item) => item.event).join(', ')
            }`,
          ))
        }, timeoutMs),
      }
      this.waiters.add(waiter)
    })
  }

  close(): void {
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer)
      waiter.reject(new Error('WebSocket closed before event arrived'))
    }
    this.waiters.clear()
    this.socket?.close()
    this.socket = undefined
  }

  id(prefix: string): string {
    return `${prefix}_${Date.now().toString(36)}_${Math.random()
      .toString(36)
      .slice(2, 10)}`
  }

  private onMessage(raw: WebSocket.RawData): void {
    const envelope = JSON.parse(raw.toString()) as WsEnvelope
    this.received.push(envelope)
    for (const waiter of this.waiters) {
      if (!waiter.predicate(envelope)) continue
      clearTimeout(waiter.timer)
      this.waiters.delete(waiter)
      waiter.resolve(envelope)
    }
  }

  private bindSocket(socket: WebSocket): void {
    socket.on('message', (raw) => this.onMessage(raw))
  }
}
