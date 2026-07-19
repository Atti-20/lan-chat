import {
  computed,
  onBeforeUnmount,
  onMounted,
  readonly,
  ref,
  shallowRef,
} from 'vue'
import { nativeBridge, type DesktopNode } from '../platform/nativeBridge'
import {
  selectedNode,
} from '../platform/nodeContext'
import { activateDesktopNode } from '../platform/desktopNodeSelection'
import { navigateToApp } from '../platform/appNavigation'
import {
  consumeDesktopNavigation,
  pendingDesktopNavigation,
} from '../platform/desktopNavigation'
import { api } from '../services/api'
import type { DiscoveredNode, NodePublicInfo } from '../types'

function webNode(
  node: DiscoveredNode,
  health: DesktopNode['health'] = 'HEALTHY',
): DesktopNode {
  return {
    ...node,
    source: 'SERVER_FALLBACK',
    health,
    latencyMs: null,
    failureCount: health === 'HEALTHY' ? 0 : 1,
    pinned: false,
    protocolVersion: 1,
  }
}

export function useNodeDiscovery() {
  const desktop = nativeBridge.runtime() === 'tauri'
  const nodeInfo = shallowRef<NodePublicInfo | null>(null)
  const discoveredNodes = ref<DesktopNode[]>([])
  const loading = shallowRef(false)
  const addingManual = shallowRef(false)
  const error = shallowRef('')
  let pollingTimer: number | null = null
  let stopListening: (() => void) | null = null
  let disposed = false
  let activatingPendingNode = false

  const nodes = computed<DesktopNode[]>(() => {
    const currentSelection = selectedNode()
    const byId = new Map<string, DesktopNode>()

    if (!desktop && nodeInfo.value) {
      const current = nodeInfo.value
      byId.set(current.nodeId, webNode({
        nodeId: current.nodeId,
        nodeName: current.nodeName,
        organizationName: current.organizationName,
        version: current.version,
        mode: current.mode,
        appUrl: `${window.location.origin}/app/`,
        secure: window.location.protocol === 'https:',
        current: true,
        lastSeenAt: new Date().toISOString(),
      }))
    }

    discoveredNodes.value.forEach((rawNode) => {
      const node = {
        ...rawNode,
        current: desktop
          ? currentSelection?.nodeId === rawNode.nodeId
          : rawNode.current,
      }
      const existing = byId.get(node.nodeId)
      if (!existing
        || (!existing.current && node.current)
        || new Date(node.lastSeenAt) > new Date(existing.lastSeenAt)) {
        byId.set(node.nodeId, node)
      }
    })

    return [...byId.values()].sort((first, second) => {
      if (first.current !== second.current) return first.current ? -1 : 1
      if (first.pinned !== second.pinned) return first.pinned ? -1 : 1
      const healthOrder: Record<DesktopNode['health'], number> = {
        HEALTHY: 0,
        PROBING: 1,
        DEGRADED: 2,
        UNKNOWN: 3,
        OFFLINE: 4,
      }
      const healthDifference = healthOrder[first.health] - healthOrder[second.health]
      if (healthDifference !== 0) return healthDifference
      return first.nodeName.localeCompare(second.nodeName, 'zh-CN')
    })
  })

  const peerCount = computed(() => desktop
    ? nodes.value.length
    : nodes.value.filter((node) => !node.current).length)

  async function refresh(): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      if (desktop) {
        await nativeBridge.refreshDiscovery()
        const current = selectedNode()
        if (current) {
          try {
            const fallbackAddresses = [...new Set(
              (await api.node.discoveries())
                .map((node) => node.appUrl)
                .filter((address) => {
                  try {
                    return new URL(address).origin !== current.origin
                  } catch {
                    return false
                  }
                }),
            )].slice(0, 32)
            if (fallbackAddresses.length > 0) {
              await nativeBridge.addServerFallbackNodes(fallbackAddresses)
            }
          } catch {
            // A selected seed node is only a fallback source. Native mDNS and
            // the persisted cache remain available when it cannot be reached.
          }
        }
        discoveredNodes.value = await nativeBridge.discoveredNodes()
      } else {
        const [info, discoveries] = await Promise.all([
          api.node.info(),
          api.node.discoveries(),
        ])
        nodeInfo.value = info
        discoveredNodes.value = (discoveries || []).map((node) => webNode(node))
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '局域网节点发现暂不可用'
    } finally {
      loading.value = false
    }
  }

  async function syncDesktopSnapshot(): Promise<void> {
    if (!desktop) return
    try {
      discoveredNodes.value = await nativeBridge.discoveredNodes()
    } catch {
      // Native events are the primary source; a transient snapshot failure does
      // not replace the last verified list with an error state.
    }
  }

  async function tryPendingNode(nextNodes = nodes.value): Promise<void> {
    if (!desktop || activatingPendingNode) return
    const target = pendingDesktopNavigation()
    if (target?.kind !== 'node' || target.nodeOrigin) return
    const node = nextNodes.find((candidate) => candidate.nodeId === target.value)
    if (!node) return
    activatingPendingNode = true
    consumeDesktopNavigation()
    try {
      await openNode(node)
    } finally {
      activatingPendingNode = false
    }
  }

  async function openNode(node: DesktopNode): Promise<void> {
    if (node.current || node.health === 'OFFLINE') return

    if (!desktop) {
      try {
        const target = new URL(node.appUrl)
        if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) {
          throw new Error('节点地址无效')
        }
        window.location.assign(target.toString())
      } catch {
        error.value = '发现的节点地址无效，请联系节点管理员'
      }
      return
    }

    try {
      if (await activateDesktopNode(node)) navigateToApp('/', true)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法安全切换节点'
    }
  }

  async function addManualNode(address: string): Promise<void> {
    if (!desktop || addingManual.value) return
    addingManual.value = true
    error.value = ''
    try {
      const node = await nativeBridge.addManualNode(address)
      const remaining = discoveredNodes.value.filter((item) => item.nodeId !== node.nodeId)
      discoveredNodes.value = [node, ...remaining]
      await openNode(node)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '节点地址验证失败'
    } finally {
      addingManual.value = false
    }
  }

  onMounted(async () => {
    if (desktop) {
      const stop = await nativeBridge.listenForNodes((nextNodes) => {
        discoveredNodes.value = nextNodes
        void tryPendingNode(nextNodes)
      })
      if (disposed) {
        stop()
        return
      }
      stopListening = stop
    }
    await refresh()
    await tryPendingNode()
    if (disposed) return
    pollingTimer = window.setInterval(
      () => void (desktop ? syncDesktopSnapshot() : refresh()),
      desktop ? 10_000 : 8_000,
    )
  })

  onBeforeUnmount(() => {
    disposed = true
    if (pollingTimer !== null) window.clearInterval(pollingTimer)
    stopListening?.()
  })

  return {
    desktop,
    nodeInfo: readonly(nodeInfo),
    nodes,
    peerCount,
    loading: readonly(loading),
    addingManual: readonly(addingManual),
    error: readonly(error),
    refresh,
    openNode,
    addManualNode,
  }
}
