import { readSession } from '../utils/storage'
import { useToast } from '../composables/useToast'
import { activateDesktopNode } from './desktopNodeSelection'
import {
  nativeBridge,
  type DesktopNavigationTarget,
} from './nativeBridge'
import { navigateToApp } from './appNavigation'
import { selectedNode } from './nodeContext'
import {
  authorizeCapacitorNotificationTarget,
  type CapacitorNavigationRejectionReason,
  NavigationDeliveryDeduper,
  normalizeNavigationTarget,
  pendingTargetBelongsToNode,
  PendingNavigationStore,
} from './navigationTarget'

export const DESKTOP_NAVIGATION_EVENT = 'lanchat:desktop-navigation'

let navigationStore: PendingNavigationStore | null = null
const targetDeduper = new NavigationDeliveryDeduper(Date.now, 3_000, 32)
const toast = useToast()

const CAPACITOR_REJECTION_COPY: Record<CapacitorNavigationRejectionReason, string> = {
  INVALID_TARGET: '通知目标格式无效，已拒绝打开。',
  NO_SELECTED_NODE: '通知无法建立节点信任，请先从节点列表手动选择节点。',
  MISSING_NODE_ORIGIN: '通知缺少可信节点标识，已拒绝打开。',
  NODE_ORIGIN_MISMATCH: '通知目标不属于当前节点，已拒绝打开。',
  NODE_ID_MISMATCH: '通知指定的节点与当前节点不一致，已拒绝打开。',
}

function pendingStore(): PendingNavigationStore {
  navigationStore ||= new PendingNavigationStore(sessionStorage)
  return navigationStore
}

function storePendingTarget(target: DesktopNavigationTarget): void {
  pendingStore().store(target)
}

function emitTarget(target: DesktopNavigationTarget): void {
  window.dispatchEvent(new CustomEvent<DesktopNavigationTarget>(
    DESKTOP_NAVIGATION_EVENT,
    { detail: target },
  ))
}

export function pendingDesktopNavigation(): DesktopNavigationTarget | null {
  return pendingStore().pending()
}

export function consumeDesktopNavigation(
  expected?: DesktopNavigationTarget,
): DesktopNavigationTarget | null {
  return pendingStore().claim(expected)
}

/**
 * 供导航消费方使用：挂起目标可能是在另一个节点上创建的（存入后用户切换了
 * 节点），消费时必须重新校验 nodeOrigin，防止在错误节点上打开同名会话。
 */
export function claimNavigationForCurrentNode(
  expected?: DesktopNavigationTarget,
): DesktopNavigationTarget | null {
  const claimed = consumeDesktopNavigation(expected)
  if (!claimed) return null
  if (nativeBridge.runtime() !== 'web'
    && !pendingTargetBelongsToNode(claimed, selectedNode())) {
    toast.push(CAPACITOR_REJECTION_COPY.NODE_ORIGIN_MISMATCH, 'warning', 4800)
    return null
  }
  return claimed
}

async function handleTarget(rawTarget: DesktopNavigationTarget): Promise<void> {
  let target = normalizeNavigationTarget(rawTarget)
  if (!target) return
  const targetKey = `${target.kind}:${target.value}:${target.nodeOrigin || ''}`
  if (!targetDeduper.accept(targetKey)) return

  if (nativeBridge.runtime() === 'capacitor') {
    const authorization = authorizeCapacitorNotificationTarget(target, selectedNode())
    if (!authorization.allowed) {
      toast.push(CAPACITOR_REJECTION_COPY[authorization.reason], 'warning', 4800)
      return
    }
    target = authorization.target
    if (target.kind === 'node') {
      const authenticated = Boolean(readSession()?.token)
      const inChat = window.location.pathname.endsWith('/chat')
      if (authenticated && !inChat) navigateToApp('/chat', true)
      else if (!authenticated && inChat) navigateToApp('/', true)
      return
    }
  }

  if (target.kind === 'node') {
    let node = target.nodeOrigin
      ? await nativeBridge.addManualNode(target.nodeOrigin).catch(() => null)
      : null
    if (!target.nodeOrigin) {
      await nativeBridge.refreshDiscovery().catch(() => undefined)
      for (let attempt = 0; attempt < 8 && !node; attempt += 1) {
        node = (await nativeBridge.discoveredNodes().catch(() => []))
          .find((candidate) => candidate.nodeId === target.value) || null
        if (!node) {
          await new Promise<void>((resolve) => window.setTimeout(resolve, 250))
        }
      }
    }
    if (!node) {
      // 发现服务可能仍在扫描；挂起按 nodeId 寻址的目标，
      // 待节点出现在发现列表后由 useNodeDiscovery.tryPendingNode 消费。
      if (!target.nodeOrigin) storePendingTarget(target)
      await nativeBridge.notify({
        title: '未找到 MeshX 节点',
        body: '请确认目标节点已启动，并与本机位于同一局域网。',
      }).catch(() => undefined)
      return
    }
    if (await activateDesktopNode(node)) navigateToApp('/', true)
    return
  }

  storePendingTarget(target)
  if (target.nodeOrigin && selectedNode()?.origin !== target.nodeOrigin) {
    const node = await nativeBridge.addManualNode(target.nodeOrigin).catch(() => null)
    if (!node) return
    if (await activateDesktopNode(node)) navigateToApp('/', true)
    else consumeDesktopNavigation()
    return
  }
  if (readSession()?.token && !window.location.pathname.endsWith('/chat')) {
    navigateToApp('/chat', true)
    return
  }
  emitTarget(target)
}

export async function installDesktopNavigation(): Promise<() => void> {
  if (nativeBridge.runtime() === 'web') return () => undefined
  const stopListening = await nativeBridge.listenForNavigation((target) => {
    void handleTarget(target)
  })
  const coldStartTarget = await nativeBridge.takePendingNavigation().catch(() => null)
  if (coldStartTarget) await handleTarget(coldStartTarget)
  return stopListening
}
