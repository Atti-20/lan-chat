import { onBeforeUnmount, readonly, shallowRef } from 'vue'
import type { WsEnvelope } from '../types'
import { readSession } from '../utils/storage'

interface UseWebSocketOptions {
  onMessage: (message: WsEnvelope) => void
  onError?: (message: string) => void
}

export function useWebSocket(options: UseWebSocketOptions) {
  const connected = shallowRef(false)
  const reconnecting = shallowRef(false)
  let socket: WebSocket | null = null
  let reconnectTimer: number | null = null
  let heartbeatTimer: number | null = null
  let attempts = 0
  let manuallyClosed = false
  const maxReconnectAttempts = 6

  function connect(): void {
    const token = readSession()?.token
    if (!token) {
      reconnecting.value = false
      return
    }
    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return
    manuallyClosed = false
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/chat?token=${encodeURIComponent(token)}`
    socket = new WebSocket(url)

    socket.onopen = () => {
      connected.value = true
      reconnecting.value = false
      attempts = 0
      stopHeartbeat()
      heartbeatTimer = window.setInterval(() => send({ type: 'ping' }), 25_000)
    }
    socket.onmessage = (event) => {
      try {
        options.onMessage(JSON.parse(event.data) as WsEnvelope)
      } catch {
        options.onError?.('收到一条无法解析的实时消息')
      }
    }
    // onclose 会接管自动重连；瞬时错误只反映在连接状态中，避免重复 Toast 干扰对话。
    socket.onerror = () => { connected.value = false }
    socket.onclose = () => {
      connected.value = false
      stopHeartbeat()
      socket = null
      if (!manuallyClosed) scheduleReconnect()
    }
  }

  function scheduleReconnect(): void {
    if (reconnectTimer !== null) return
    if (attempts >= maxReconnectAttempts) {
      reconnecting.value = false
      options.onError?.('实时连接已断开，请刷新页面')
      return
    }
    reconnecting.value = true
    attempts += 1
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      connect()
    }, Math.min(1000 * 2 ** attempts, 15_000))
  }

  function send(message: Record<string, unknown>): boolean {
    if (socket?.readyState !== WebSocket.OPEN) return false
    socket.send(JSON.stringify(message))
    return true
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }

  function disconnect(): void {
    manuallyClosed = true
    if (reconnectTimer !== null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    stopHeartbeat()
    socket?.close()
    socket = null
    attempts = 0
    connected.value = false
    reconnecting.value = false
  }

  onBeforeUnmount(disconnect)

  return {
    connected: readonly(connected),
    reconnecting: readonly(reconnecting),
    connect,
    disconnect,
    send,
  }
}
