import { computed, onBeforeUnmount, onMounted, readonly, ref, shallowRef } from 'vue'
import { api } from '../services/api'
import type { DiscoveredNode, NodePublicInfo } from '../types'

export function useNodeDiscovery() {
  const nodeInfo = shallowRef<NodePublicInfo | null>(null)
  const discoveredNodes = ref<DiscoveredNode[]>([])
  const loading = shallowRef(false)
  const error = shallowRef('')
  let pollingTimer: number | null = null

  const nodes = computed<DiscoveredNode[]>(() => {
    const byId = new Map<string, DiscoveredNode>()
    const current = nodeInfo.value
    if (current) {
      byId.set(current.nodeId, {
        nodeId: current.nodeId,
        nodeName: current.nodeName,
        organizationName: current.organizationName,
        version: current.version,
        mode: current.mode,
        appUrl: `${window.location.origin}/app/`,
        secure: window.location.protocol === 'https:',
        current: true,
        lastSeenAt: new Date().toISOString(),
      })
    }
    discoveredNodes.value.forEach((node) => {
      const existing = byId.get(node.nodeId)
      if (!existing || (!existing.current && node.current)) byId.set(node.nodeId, node)
      else if (!existing.current && new Date(node.lastSeenAt) > new Date(existing.lastSeenAt)) byId.set(node.nodeId, node)
    })
    return [...byId.values()].sort((first, second) => {
      if (first.current !== second.current) return first.current ? -1 : 1
      return first.nodeName.localeCompare(second.nodeName, 'zh-CN')
    })
  })

  const peerCount = computed(() => nodes.value.filter((node) => !node.current).length)

  async function refresh(): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      const [info, discoveries] = await Promise.all([api.node.info(), api.node.discoveries()])
      nodeInfo.value = info
      discoveredNodes.value = discoveries || []
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '局域网节点发现暂不可用'
    } finally {
      loading.value = false
    }
  }

  function openNode(node: DiscoveredNode): void {
    if (node.current) return
    try {
      const target = new URL(node.appUrl)
      if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) {
        throw new Error('节点地址无效')
      }
      window.location.assign(target.toString())
    } catch {
      error.value = '发现的节点地址无效，请联系节点管理员'
    }
  }

  onMounted(() => {
    void refresh()
    pollingTimer = window.setInterval(() => void refresh(), 8_000)
  })

  onBeforeUnmount(() => {
    if (pollingTimer !== null) window.clearInterval(pollingTimer)
  })

  return {
    nodeInfo: readonly(nodeInfo),
    nodes,
    peerCount,
    loading: readonly(loading),
    error: readonly(error),
    refresh,
    openNode,
  }
}
