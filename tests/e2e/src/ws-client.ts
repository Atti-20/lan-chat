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

export interface WsClose {
  code: number
  reason: string
}

type Predicate = (envelope: WsEnvelope) => boolean

interface Waiter {
  predicate: Predicate
  afterIndex: number
  resolve: (envelope: WsEnvelope) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

interface CloseWaiter {
  resolve: (close: WsClose) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

export class WsClient {
  private socket?: WebSocket
  private readonly received: WsEnvelope[] = []
  private readonly waiters = new Set<Waiter>()
  private readonly closeWaiters = new Set<CloseWaiter>()
  private lastClose?: WsClose

  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
  ) {}

  get messageCount(): number {
    return this.received.length
  }

  get isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  countWhere(predicate: (envelope: WsEnvelope) => boolean): number {
    return this.received.filter(predicate).length
  }

  async connect(): Promise<void> {
    if (this.socket && this.socket.readyState !== WebSocket.CLOSED) {
      throw new Error('WebSocket is already connected or connecting')
    }
    this.lastClose = undefined
    const wsUrl = new URL('/ws/chat', this.baseUrl)
    wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new WebSocket(wsUrl, { origin: this.baseUrl })
    this.socket = socket
    this.bindSocket(socket)

    await new Promise<void>((resolve, reject) => {
      const onOpen = () => {
        socket.off('error', onError)
        resolve()
      }
      const onError = (error: Error) => {
        socket.off('open', onOpen)
        reject(error)
      }
      socket.once('open', onOpen)
      socket.once('error', onError)
    })

    const cursor = this.messageCount
    const authenticated = this.waitFor(
      (envelope) => envelope.event === 'AUTH_OK',
      15_000,
      cursor,
    )
    this.send('AUTH', { token: this.token }, { requestId: this.id('auth') })
    await authenticated
  }

  /** Requests the authoritative committed message snapshot for every accessible conversation. */
  async syncAll(timeoutMs = 15_000): Promise<WsEnvelope> {
    const requestId = this.id('sync')
    const cursor = this.messageCount
    const response = this.waitFor(
      (envelope) => envelope.event === 'SYNC_RESPONSE' && envelope.requestId === requestId,
      timeoutMs,
      cursor,
    )
    this.send('SYNC_REQUEST', { positions: {}, limit: 200 }, { requestId })
    return response
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

  waitFor(
    predicate: Predicate,
    timeoutMs = 15_000,
    afterIndex = 0,
  ): Promise<WsEnvelope> {
    const existing = this.received.slice(afterIndex).find(predicate)
    if (existing) return Promise.resolve(existing)

    return new Promise((resolve, reject) => {
      const waiter: Waiter = {
        predicate,
        afterIndex,
        resolve,
        reject,
        timer: setTimeout(() => {
          this.waiters.delete(waiter)
          reject(new Error(
            `Timed out waiting for WebSocket event after index ${afterIndex}; received: ${
              this.received.map((item) => item.event).join(', ')
            }`,
          ))
        }, timeoutMs),
      }
      this.waiters.add(waiter)
    })
  }

  waitForClose(timeoutMs = 15_000): Promise<WsClose> {
    if (this.lastClose) return Promise.resolve(this.lastClose)
    return new Promise((resolve, reject) => {
      const waiter: CloseWaiter = {
        resolve,
        reject,
        timer: setTimeout(() => {
          this.closeWaiters.delete(waiter)
          reject(new Error('Timed out waiting for WebSocket close'))
        }, timeoutMs),
      }
      this.closeWaiters.add(waiter)
    })
  }

  async disconnect(): Promise<WsClose | null> {
    const socket = this.socket
    if (!socket || socket.readyState === WebSocket.CLOSED) return this.lastClose || null
    const closed = this.waitForClose(5_000)
    socket.close(1000, 'E2E complete')
    return closed.catch(() => null)
  }

  close(): void {
    const socket = this.socket
    if (socket && socket.readyState !== WebSocket.CLOSED) socket.close()
    this.socket = undefined
    this.rejectEventWaiters(new Error('WebSocket closed by E2E client'))
  }

  id(prefix: string): string {
    return `${prefix}_${Date.now().toString(36)}_${Math.random()
      .toString(36)
      .slice(2, 10)}`
  }

  private onMessage(raw: WebSocket.RawData): void {
    let envelope: WsEnvelope
    try {
      envelope = JSON.parse(raw.toString()) as WsEnvelope
    } catch {
      this.rejectEventWaiters(new Error(`WebSocket returned invalid JSON: ${raw.toString()}`))
      return
    }
    this.received.push(envelope)
    const index = this.received.length - 1
    for (const waiter of this.waiters) {
      if (index < waiter.afterIndex || !waiter.predicate(envelope)) continue
      clearTimeout(waiter.timer)
      this.waiters.delete(waiter)
      waiter.resolve(envelope)
    }
  }

  private onClose(code: number, reason: Buffer): void {
    const close = { code, reason: reason.toString() }
    this.lastClose = close
    this.socket = undefined
    for (const waiter of this.closeWaiters) {
      clearTimeout(waiter.timer)
      waiter.resolve(close)
    }
    this.closeWaiters.clear()
    this.rejectEventWaiters(
      new Error(`WebSocket closed (${close.code}${close.reason ? `: ${close.reason}` : ''})`),
    )
  }

  private rejectEventWaiters(error: Error): void {
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer)
      waiter.reject(error)
    }
    this.waiters.clear()
  }

  private bindSocket(socket: WebSocket): void {
    socket.on('message', (raw) => this.onMessage(raw))
    socket.on('close', (code, reason) => this.onClose(code, reason))
    socket.on('error', (error) => {
      if (socket.readyState === WebSocket.OPEN) {
        this.rejectEventWaiters(error)
      }
    })
  }
}
