/** Text frame transport only; authentication, ordering, sync and retries are application policy. */
export interface RealtimeSocket {
  readonly readyState: number
  onopen: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onerror: (() => void) | null
  onclose: (() => void) | null
  send(data: string): void
  close(): void
}

export interface RealtimePort {
  open(): Promise<RealtimeSocket>
}

export const SOCKET_CONNECTING = 0
export const SOCKET_OPEN = 1
export const SOCKET_CLOSING = 2
export const SOCKET_CLOSED = 3
