import type { DesktopNode } from './nativeBridge'

export interface CurrentNodeSession {
  nodeId: string
  origin: string
  apiBasePath: string
}

export interface NodeSwitchDeps {
  selectedNode: () => CurrentNodeSession | null
  confirm: (message: string, options: {
    title: string
    kind: 'warning'
    okLabel: string
    cancelLabel: string
  }) => Promise<boolean>
  nativeLogout: (origin: string, apiBasePath: string, token: string | undefined) => Promise<unknown>
  clearNodeSession: (origin: string) => Promise<unknown>
  clearLocalChatDatabase: () => Promise<unknown>
  clearSession: () => void
  clearCacheOwner: () => void
  selectNode: (node: DesktopNode) => void
  readToken: () => string | undefined
}

/**
 * 节点切换的固定顺序：先退出旧节点（网络失败也继续），再清理旧节点原生
 * Cookie，之后才清空本地缓存并选中新节点。顺序变化会导致旧节点会话残留。
 */
export async function performNodeSwitch(deps: NodeSwitchDeps, node: DesktopNode): Promise<boolean> {
  const current = deps.selectedNode()
  if (current?.nodeId === node.nodeId
    && current.origin === new URL(node.apiOrigin || node.appUrl).origin) {
    return false
  }

  if (current) {
    const confirmed = await deps.confirm(
      `切换到“${node.nodeName}”会退出当前节点并清理本地聊天缓存。是否继续？`,
      {
        title: '切换 MeshX 节点',
        kind: 'warning',
        okLabel: '切换节点',
        cancelLabel: '取消',
      },
    )
    if (!confirmed) return false
  }

  if (current) {
    await deps.nativeLogout(current.origin, current.apiBasePath, deps.readToken())
      .catch(() => undefined)
    // 旧节点可能已死亡并被原生发现列表淘汰导致清理被拒；
    // 清不掉旧 Cookie 不能阻止用户离开死节点。
    await deps.clearNodeSession(current.origin).catch(() => undefined)
  }
  await deps.clearLocalChatDatabase()
  deps.clearSession()
  deps.clearCacheOwner()
  deps.selectNode(node)
  return true
}
