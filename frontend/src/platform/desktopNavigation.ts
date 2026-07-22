import { readSession } from '../utils/storage'
import { activateDesktopNode } from './desktopNodeSelection'
import {
  nativeBridge,
  type DesktopNavigationKind,
  type DesktopNavigationTarget,
} from './nativeBridge'
import { navigateToApp } from './appNavigation'
import { selectedNode } from './nodeContext'

const PENDING_TARGET_KEY = 'lanchat_desktop_navigation_v1'
export const DESKTOP_NAVIGATION_EVENT = 'lanchat:desktop-navigation'

function normalizedOrigin(value?: string | null): string | null {
  if (typeof value !== 'string' || !value.trim()) return null
  try {
    const parsed = new URL(value.trim())
    if (!['http:', 'https:'].includes(parsed.protocol)
      || parsed.username
      || parsed.password
      || parsed.pathname !== '/'
      || parsed.search
      || parsed.hash) {
      return null
    }
    return parsed.origin
  } catch {
    return null
  }
}

function normalizeTarget(target: DesktopNavigationTarget): DesktopNavigationTarget | null {
  if (!target
    || typeof target.value !== 'string'
    || !['node', 'room', 'conversation', 'broadcast'].includes(target.kind)) {
    return null
  }
  const value = target.value.trim()
  if (target.kind === 'node') {
    const origin = normalizedOrigin(
      target.nodeOrigin || (value.includes('://') ? value : null),
    )
    if (origin) {
      return { kind: 'node', value: origin, nodeOrigin: origin }
    }
    return /^[a-z0-9_-]{3,64}$/.test(value)
      ? { kind: 'node', value }
      : null
  }
  const validators: Record<DesktopNavigationKind, RegExp> = {
    node: /$^/,
    room: /^[A-Za-z0-9_-]{3,64}$/,
    conversation: /^[A-Za-z0-9._:-]{1,200}$/,
    broadcast: /^[1-9]\d{0,18}$/,
  }
  if (!validators[target.kind]?.test(value)) return null
  const origin = target.nodeOrigin ? normalizedOrigin(target.nodeOrigin) : null
  if (target.nodeOrigin && !origin) return null
  return {
    kind: target.kind,
    value,
    nodeOrigin: origin,
  }
}

function storePendingTarget(target: DesktopNavigationTarget): void {
  sessionStorage.setItem(PENDING_TARGET_KEY, JSON.stringify(target))
}

function emitTarget(target: DesktopNavigationTarget): void {
  window.dispatchEvent(new CustomEvent<DesktopNavigationTarget>(
    DESKTOP_NAVIGATION_EVENT,
    { detail: target },
  ))
}

export function pendingDesktopNavigation(): DesktopNavigationTarget | null {
  try {
    const raw = sessionStorage.getItem(PENDING_TARGET_KEY)
    if (!raw) return null
    return normalizeTarget(JSON.parse(raw) as DesktopNavigationTarget)
  } catch {
    sessionStorage.removeItem(PENDING_TARGET_KEY)
    return null
  }
}

export function consumeDesktopNavigation(): DesktopNavigationTarget | null {
  const target = pendingDesktopNavigation()
  sessionStorage.removeItem(PENDING_TARGET_KEY)
  return target
}

async function handleTarget(rawTarget: DesktopNavigationTarget): Promise<void> {
  await nativeBridge.takePendingNavigation().catch(() => null)
  const target = normalizeTarget(rawTarget)
  if (!target) return

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
    navigateToApp('/chat')
    return
  }
  emitTarget(target)
}

export async function installDesktopNavigation(): Promise<() => void> {
  if (nativeBridge.runtime() !== 'tauri') return () => undefined
  const stopListening = await nativeBridge.listenForNavigation((target) => {
    void handleTarget(target)
  })
  const coldStartTarget = await nativeBridge.takePendingNavigation().catch(() => null)
  if (coldStartTarget) await handleTarget(coldStartTarget)
  return stopListening
}
