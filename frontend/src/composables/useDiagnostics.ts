import { computed, onBeforeUnmount, onMounted, readonly, shallowRef } from 'vue'
import type { Ref } from 'vue'
import { api } from '../services/api'
import type {
  AdminDiagnostics,
  ConnectionPath,
  ConnectionState,
  NodePublicInfo,
} from '../types'

interface DiagnosticsSources {
  connectionState: Readonly<Ref<ConnectionState>>
  reconnectAttempts: Readonly<Ref<number>>
  latencyMs: Readonly<Ref<number | null>>
  lastHeartbeatAt: Readonly<Ref<string | null>>
  lastSyncAt: Readonly<Ref<string | null>>
  pendingCount: Readonly<Ref<number>>
  failedCount: Readonly<Ref<number>>
  isAdmin: Readonly<Ref<boolean>>
}

export function useDiagnostics(sources: DiagnosticsSources) {
  const nodeInfo = shallowRef<NodePublicInfo | null>(null)
  const adminDiagnostics = shallowRef<AdminDiagnostics | null>(null)
  const loading = shallowRef(false)
  const error = shallowRef('')
  const refreshedAt = shallowRef<string | null>(null)
  let refreshTimer: number | null = null

  const connectionPath = computed<ConnectionPath>(() => classifyConnectionPath(window.location.hostname))
  const nodeAddress = computed(() => window.location.origin)
  const webSocketAddress = computed(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/chat`
  })
  const browserCapabilities = Object.freeze({
    webSocket: 'WebSocket' in window,
    indexedDb: 'indexedDB' in window,
    notifications: 'Notification' in window,
    serviceWorker: 'serviceWorker' in navigator,
    online: navigator.onLine,
  })

  async function refresh(): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      const nodeRequest = api.node.info()
      const adminRequest = sources.isAdmin.value
        ? api.admin.diagnostics()
        : Promise.resolve(null)
      const [nodeResult, adminResult] = await Promise.allSettled([nodeRequest, adminRequest])
      if (nodeResult.status === 'fulfilled') nodeInfo.value = nodeResult.value
      else throw nodeResult.reason
      if (adminResult.status === 'fulfilled') adminDiagnostics.value = adminResult.value
      else if (sources.isAdmin.value) throw adminResult.reason
      refreshedAt.value = new Date().toISOString()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '诊断信息暂时不可用'
    } finally {
      loading.value = false
    }
  }

  function exportDiagnostics(): void {
    const report = {
      exportedAt: new Date().toISOString(),
      node: nodeInfo.value,
      client: {
        address: nodeAddress.value,
        webSocketAddress: webSocketAddress.value,
        connectionPath: connectionPath.value,
        connectionState: sources.connectionState.value,
        latencyMs: sources.latencyMs.value,
        reconnectAttempts: sources.reconnectAttempts.value,
        lastHeartbeatAt: sources.lastHeartbeatAt.value,
        lastSyncAt: sources.lastSyncAt.value,
        pendingMessages: sources.pendingCount.value,
        failedMessages: sources.failedCount.value,
        userAgent: navigator.userAgent.slice(0, 200),
        capabilities: browserCapabilities,
      },
      administrator: sources.isAdmin.value ? adminDiagnostics.value : undefined,
    }
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `lanchat-diagnostics-${Date.now()}.json`
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  async function clearBrowserCaches(): Promise<number> {
    if (!('caches' in window)) return 0
    const names = await window.caches.keys()
    const results = await Promise.all(names.map((name) => window.caches.delete(name)))
    return results.filter(Boolean).length
  }

  onMounted(() => {
    void refresh()
    refreshTimer = window.setInterval(() => void refresh(), 30_000)
  })

  onBeforeUnmount(() => {
    if (refreshTimer !== null) window.clearInterval(refreshTimer)
  })

  return {
    nodeInfo: readonly(nodeInfo),
    adminDiagnostics: readonly(adminDiagnostics),
    loading: readonly(loading),
    error: readonly(error),
    refreshedAt: readonly(refreshedAt),
    connectionPath,
    nodeAddress,
    webSocketAddress,
    browserCapabilities,
    refresh,
    exportDiagnostics,
    clearBrowserCaches,
  }
}

function classifyConnectionPath(hostname: string): ConnectionPath {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  if (host === 'localhost' || host === '::1' || host.startsWith('127.')) return 'LOCAL'
  if (host.endsWith('.local') || host.startsWith('10.') || host.startsWith('192.168.')
    || host.startsWith('169.254.') || /^172\.(1[6-9]|2\d|3[01])\./.test(host)
    || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80:')) return 'LAN'
  return 'REMOTE'
}
