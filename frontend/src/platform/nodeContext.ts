import type { DesktopNode } from './nativeBridge'
import { isNativeNodeRuntime } from './mobileRuntime'

const SELECTED_NODE_KEY = 'lanchat_native_node_v1'
const LEGACY_DESKTOP_NODE_KEY = 'lanchat_desktop_node_v1'

export interface SelectedNode {
  nodeId: string
  nodeName: string
  origin: string
  apiBasePath: string
  webSocketPath: string
  healthPath: string
  appPath: string
  secure: boolean
}

export class NodeSelectionRequiredError extends Error {
  constructor() {
    super('请先选择一个可连接的 MeshX 节点')
  }
}

function normalizedPath(value: string | undefined, fallback: string): string {
  const candidate = (value || fallback).trim()
  const path = candidate.startsWith('/') ? candidate : `/${candidate}`
  return path.replace(/\/{2,}/g, '/').replace(/\/$/, '') || '/'
}

function safeOrigin(value: string): string {
  const parsed = new URL(value)
  if (!['http:', 'https:'].includes(parsed.protocol)
    || parsed.username
    || parsed.password
    || !parsed.hostname) {
    throw new Error('节点地址必须是有效的 HTTP 或 HTTPS 地址')
  }
  return parsed.origin
}

export function selectedNode(): SelectedNode | null {
  if (!isNativeNodeRuntime()) return null
  try {
    const value = localStorage.getItem(SELECTED_NODE_KEY)
      || localStorage.getItem(LEGACY_DESKTOP_NODE_KEY)
    if (!value) return null
    const stored = JSON.parse(value) as SelectedNode
    return {
      ...stored,
      origin: safeOrigin(stored.origin),
      apiBasePath: normalizedPath(stored.apiBasePath, '/api/v1'),
      webSocketPath: normalizedPath(stored.webSocketPath, '/ws/chat'),
      healthPath: normalizedPath(stored.healthPath, '/api/v1/node/health'),
      appPath: normalizedPath(stored.appPath, '/app/'),
    }
  } catch {
    localStorage.removeItem(SELECTED_NODE_KEY)
    return null
  }
}

export function selectNode(node: DesktopNode): SelectedNode {
  const origin = safeOrigin(node.apiOrigin || node.appUrl)
  const selection: SelectedNode = {
    nodeId: node.nodeId,
    nodeName: node.nodeName,
    origin,
    apiBasePath: normalizedPath(node.apiBasePath, '/api/v1'),
    webSocketPath: normalizedPath(node.webSocketPath, '/ws/chat'),
    healthPath: normalizedPath(node.healthPath, '/api/v1/node/health'),
    appPath: normalizedPath(node.appPath, '/app/'),
    secure: node.secure,
  }
  localStorage.setItem(SELECTED_NODE_KEY, JSON.stringify(selection))
  return selection
}

export function clearSelectedNode(): void {
  localStorage.removeItem(SELECTED_NODE_KEY)
  localStorage.removeItem(LEGACY_DESKTOP_NODE_KEY)
}

export function currentNodeKey(): string {
  if (!isNativeNodeRuntime()) return window.location.origin
  const node = selectedNode()
  return node ? `${node.nodeId}@${node.origin}` : 'native:unselected'
}

export function currentNodeOrigin(): string {
  if (!isNativeNodeRuntime()) return window.location.origin
  const node = selectedNode()
  if (!node) throw new NodeSelectionRequiredError()
  return node.origin
}

export function currentNodeApiBasePath(): string {
  if (!isNativeNodeRuntime()) return '/api/v1'
  const node = selectedNode()
  if (!node) throw new NodeSelectionRequiredError()
  return node.apiBasePath
}

export function apiUrl(path: string): string {
  if (!isNativeNodeRuntime()) return `/api/v1${normalizedPath(path, '/')}`
  const node = selectedNode()
  if (!node) throw new NodeSelectionRequiredError()
  return new URL(`${node.apiBasePath}${normalizedPath(path, '/')}`, node.origin).toString()
}

export function webSocketUrl(): string {
  if (!isNativeNodeRuntime()) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/chat`
  }
  const node = selectedNode()
  if (!node) throw new NodeSelectionRequiredError()
  const url = new URL(node.webSocketPath, node.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

export function resourceUrl(value: string): string {
  if (!value || value.startsWith('blob:') || value.startsWith('data:')) return value
  if (!isNativeNodeRuntime()) return value
  try {
    return new URL(value, currentNodeOrigin()).toString()
  } catch {
    return value
  }
}
