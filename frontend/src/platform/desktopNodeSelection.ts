import { clearLocalChatDatabase } from '../services/localChatDb'
import {
  clearCacheOwner,
  clearSession,
  readSession,
} from '../utils/storage'
import type { DesktopNode } from './nativeBridge'
import { nativeBridge } from './nativeBridge'
import {
  selectNode,
  selectedNode,
} from './nodeContext'

export async function activateDesktopNode(node: DesktopNode): Promise<boolean> {
  const current = selectedNode()
  if (current?.nodeId === node.nodeId && current.origin === new URL(node.apiOrigin || node.appUrl).origin) {
    return false
  }

  if (current) {
    const confirmed = await nativeBridge.confirm(
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
    await nativeBridge.desktopLogout(
      current.origin,
      current.apiBasePath,
      readSession()?.token,
    )
      .catch(() => undefined)
  }
  await clearLocalChatDatabase()
  clearSession()
  clearCacheOwner()
  selectNode(node)
  return true
}
