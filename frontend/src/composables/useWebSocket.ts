import { computed, onBeforeUnmount, readonly, shallowRef } from 'vue'
import type { ConnectionState, WsEnvelope } from '../types'
import { createClientMessageId } from '../utils/id'
import { readSession } from '../utils/storage'

interface UseWebSocketOptions {
  onMessage: (message: WsEnvelope) => void | Promise<void>
  onReady?: () => void | Promise<void>
  onError?: (message: string) => void
  refreshAuth?: () => Promise<boolean>
  onAuthFailed?: (reason: 'TOKEN_EXPIRED' | 'FORCE_LOGOUT') => void
}

export function useWebSocket(options: UseWebSocketOptions) {
  const state = shallowRef<ConnectionState>('OFFLINE')
  const reconnectAttempts = shallowRef(0)
  const latencyMs = shallowRef<number | null>(null)
  const lastSyncAt = shallowRef<string | null>(null)
  // SYNCING 阶段已完成认证，但尚未补齐服务端序列缺口；业务发送必须等同步结束。
  const connected = computed(() => ['ONLINE', 'DEGRADED'].includes(state.value))
  const reconnecting = computed(() => state.value === 'RECONNECTING')

  let socket: WebSocket | null = null
  let reconnectTimer: number | null = null
  let heartbeatTimer: number | null = null
  let lastPingAt = 0
  let lastPongAt = 0
  let manuallyClosed = false
  let authenticated = false
  let refreshingAuth = false

  function createEnvelope(
    event: string,
    payload: Record<string, unknown> = {},
    metadata: Partial<Pick<WsEnvelope, 'requestId' | 'clientMsgId' | 'conversationId'>> = {},
  ): WsEnvelope {
    return {
      version: 1,
      event,
      timestamp: Date.now(),
      payload,
      ...metadata,
    }
  }

  function connect(): void {
    const token = readSession()?.token
    if (!token || socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return
    if (!navigator.onLine) {
      state.value = 'OFFLINE'
      return
    }

    manuallyClosed = false
    authenticated = false
    state.value = reconnectAttempts.value > 0 ? 'RECONNECTING' : 'CONNECTING'
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const nextSocket = new WebSocket(`${protocol}//${window.location.host}/ws/chat`)
    socket = nextSocket

    nextSocket.onopen = () => {
      if (socket !== nextSocket) return
      state.value = 'AUTHENTICATING'
      sendRaw(createEnvelope('AUTH', { token }, { requestId: createRequestId() }))
    }

    nextSocket.onmessage = (messageEvent) => {
      if (socket !== nextSocket) return
      void handleMessage(messageEvent.data)
    }

    nextSocket.onerror = () => {
      if (socket === nextSocket && state.value !== 'OFFLINE') state.value = 'DEGRADED'
    }

    nextSocket.onclose = () => {
      if (socket !== nextSocket) return
      socket = null
      authenticated = false
      stopHeartbeat()
      if (!manuallyClosed && !refreshingAuth) scheduleReconnect()
    }
  }

  async function handleMessage(raw: unknown): Promise<void> {
    let envelope: WsEnvelope
    try {
      envelope = JSON.parse(String(raw)) as WsEnvelope
    } catch {
      options.onError?.('收到一条无法解析的实时消息')
      return
    }
    if (envelope.version !== 1 || !envelope.event) {
      options.onError?.('实时协议版本不兼容')
      return
    }

    if (envelope.event === 'AUTH_OK') {
      authenticated = true
      reconnectAttempts.value = 0
      state.value = 'SYNCING'
      startHeartbeat()
      try {
        await options.onReady?.()
        lastSyncAt.value = new Date().toISOString()
        state.value = 'ONLINE'
      } catch (cause) {
        state.value = 'DEGRADED'
        options.onError?.(cause instanceof Error ? cause.message : '遗漏消息同步失败')
      }
      return
    }

    if (envelope.event === 'PONG') {
      lastPongAt = Date.now()
      latencyMs.value = lastPingAt > 0 ? Math.max(0, lastPongAt - lastPingAt) : null
      return
    }

    if (envelope.event === 'TOKEN_EXPIRED') {
      await refreshExpiredSession()
      return
    }

    if (envelope.event === 'FORCE_LOGOUT') {
      disconnect()
      options.onAuthFailed?.('FORCE_LOGOUT')
      return
    }

    await options.onMessage(envelope)
  }

  async function refreshExpiredSession(): Promise<void> {
    if (refreshingAuth) return
    refreshingAuth = true
    closeSocket()
    const refreshed = await options.refreshAuth?.().catch(() => false) ?? false
    refreshingAuth = false
    if (refreshed) {
      reconnectAttempts.value = 0
      connect()
      return
    }
    manuallyClosed = true
    state.value = 'OFFLINE'
    options.onAuthFailed?.('TOKEN_EXPIRED')
  }

  function scheduleReconnect(): void {
    if (reconnectTimer !== null || manuallyClosed) return
    reconnectAttempts.value += 1
    state.value = navigator.onLine ? 'RECONNECTING' : 'OFFLINE'
    const exponent = Math.min(reconnectAttempts.value - 1, 5)
    const baseDelay = Math.min(1_000 * 2 ** exponent, 30_000)
    const jitter = Math.floor(Math.random() * Math.max(250, baseDelay * 0.25))
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      connect()
    }, baseDelay + jitter)
  }

  function send(envelope: WsEnvelope): boolean {
    if (!authenticated || socket?.readyState !== WebSocket.OPEN) return false
    return sendRaw({ ...envelope, version: 1, timestamp: Date.now() })
  }

  function sendEvent(
    event: string,
    payload: Record<string, unknown> = {},
    metadata: Partial<Pick<WsEnvelope, 'requestId' | 'clientMsgId' | 'conversationId'>> = {},
  ): boolean {
    return send(createEnvelope(event, payload, metadata))
  }

  function sendRaw(envelope: WsEnvelope): boolean {
    if (socket?.readyState !== WebSocket.OPEN) return false
    socket.send(JSON.stringify(envelope))
    return true
  }

  function startHeartbeat(): void {
    stopHeartbeat()
    lastPongAt = Date.now()
    heartbeatTimer = window.setInterval(() => {
      if (Date.now() - lastPongAt > 60_000) {
        closeSocket()
        scheduleReconnect()
        return
      }
      lastPingAt = Date.now()
      sendEvent('PING', {}, { requestId: createRequestId() })
    }, 25_000)
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }

  function closeSocket(): void {
    const current = socket
    socket = null
    authenticated = false
    stopHeartbeat()
    if (!current) return
    current.onopen = null
    current.onmessage = null
    current.onerror = null
    current.onclose = null
    current.close()
  }

  function reconnect(): void {
    manuallyClosed = false
    if (reconnectTimer !== null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    reconnectAttempts.value = 0
    closeSocket()
    connect()
  }

  function disconnect(): void {
    manuallyClosed = true
    if (reconnectTimer !== null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    closeSocket()
    reconnectAttempts.value = 0
    latencyMs.value = null
    state.value = 'OFFLINE'
  }

  function handleOnline(): void {
    if (!manuallyClosed && !hasActiveSocket()) reconnect()
  }

  function handleOffline(): void {
    state.value = 'OFFLINE'
    closeSocket()
  }

  function handleVisibility(): void {
    if (document.visibilityState === 'visible' && !hasActiveSocket() && !manuallyClosed) reconnect()
  }

  function hasActiveSocket(): boolean {
    return socket?.readyState === WebSocket.CONNECTING || socket?.readyState === WebSocket.OPEN
  }

  window.addEventListener('online', handleOnline)
  window.addEventListener('offline', handleOffline)
  document.addEventListener('visibilitychange', handleVisibility)

  onBeforeUnmount(() => {
    window.removeEventListener('online', handleOnline)
    window.removeEventListener('offline', handleOffline)
    document.removeEventListener('visibilitychange', handleVisibility)
    disconnect()
  })

  return {
    state: readonly(state),
    connected,
    reconnecting,
    reconnectAttempts: readonly(reconnectAttempts),
    latencyMs: readonly(latencyMs),
    lastSyncAt: readonly(lastSyncAt),
    connect,
    reconnect,
    disconnect,
    send,
    sendEvent,
  }
}

function createRequestId(): string {
  return `req_${createClientMessageId()}`
}
