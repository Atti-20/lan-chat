import type { AuthSession, DiscoveredNode } from '../types'

export type RuntimeKind = 'web' | 'tauri'
export type DesktopPlatform = 'web' | 'macos' | 'windows' | 'linux' | 'unknown'
export type DesktopNodeSource = 'MDNS' | 'SERVER_FALLBACK' | 'CACHE' | 'MANUAL'
export type DesktopNodeHealth = 'UNKNOWN' | 'PROBING' | 'HEALTHY' | 'DEGRADED' | 'OFFLINE'
export type DesktopNavigationKind = 'node' | 'room' | 'conversation' | 'broadcast'

export interface RuntimeInfo {
  runtime: RuntimeKind
  platform: DesktopPlatform
  version: string
}

export interface DesktopNode extends DiscoveredNode {
  source: DesktopNodeSource
  health: DesktopNodeHealth
  latencyMs?: number | null
  failureCount: number
  pinned: boolean
  protocolVersion: number
  apiOrigin?: string
  apiBasePath?: string
  webSocketPath?: string
  healthPath?: string
  appPath?: string
  lastSuccessfulAt?: string | null
}

export interface DesktopNavigationTarget {
  kind: DesktopNavigationKind
  value: string
  nodeOrigin?: string | null
}

export interface NotificationInput {
  title: string
  body: string
  target?: DesktopNavigationTarget
}

export interface UpdateResult {
  status: 'UP_TO_DATE' | 'AVAILABLE' | 'INSTALLED' | 'UNCONFIGURED' | 'UNSUPPORTED'
  currentVersion?: string
  version?: string
  notes?: string
}

export interface ConfirmOptions {
  title?: string
  kind?: 'info' | 'warning' | 'error'
  okLabel?: string
  cancelLabel?: string
}

export interface NativeBridge {
  runtime(): RuntimeKind
  runtimeInfo(): Promise<RuntimeInfo>
  confirm(message: string, options?: ConfirmOptions): Promise<boolean>
  discoveredNodes(): Promise<DesktopNode[]>
  refreshDiscovery(): Promise<void>
  addManualNode(address: string): Promise<DesktopNode>
  addServerFallbackNodes(addresses: string[]): Promise<DesktopNode[]>
  desktopLogin(
    origin: string,
    apiBasePath: string,
    username: string,
    password: string,
  ): Promise<AuthSession>
  desktopRefresh(origin: string, apiBasePath: string): Promise<AuthSession | null>
  desktopLogout(origin: string, apiBasePath: string, accessToken?: string): Promise<void>
  notify(input: NotificationInput): Promise<void>
  autostartEnabled(): Promise<boolean>
  setAutostart(enabled: boolean): Promise<void>
  checkForUpdate(install?: boolean): Promise<UpdateResult>
  takePendingNavigation(): Promise<DesktopNavigationTarget | null>
  listenForNodes(listener: (nodes: DesktopNode[]) => void): Promise<() => void>
  listenForNavigation(listener: (target: DesktopNavigationTarget) => void): Promise<() => void>
}

function isTauriRuntime(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

function desktopDeviceName(): string {
  const platform = navigator.platform || 'Desktop'
  return `LANChat Desktop (${platform})`.slice(0, 100)
}

const webBridge: NativeBridge = {
  runtime: () => 'web',

  runtimeInfo: async () => ({
    runtime: 'web',
    platform: 'web',
    version: import.meta.env.VITE_APP_VERSION || 'web',
  }),

  confirm: async (message) => window.confirm(message),
  discoveredNodes: async () => [],
  refreshDiscovery: async () => undefined,
  addManualNode: async () => {
    throw new Error('网页模式不支持原生节点验证')
  },
  addServerFallbackNodes: async () => [],
  desktopLogin: async () => {
    throw new Error('网页模式不支持桌面认证')
  },
  desktopRefresh: async () => null,
  desktopLogout: async () => undefined,

  notify: async ({ title, body }) => {
    if (!('Notification' in window)) return
    const permission = Notification.permission === 'default'
      ? await Notification.requestPermission()
      : Notification.permission
    if (permission === 'granted') new Notification(title, { body })
  },

  autostartEnabled: async () => false,
  setAutostart: async () => {
    throw new Error('网页模式不支持开机自启')
  },
  checkForUpdate: async () => ({ status: 'UNSUPPORTED' }),
  takePendingNavigation: async () => null,
  listenForNodes: async () => () => undefined,
  listenForNavigation: async () => () => undefined,
}

const tauriBridge: NativeBridge = {
  runtime: () => 'tauri',

  runtimeInfo: async () => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<RuntimeInfo>('runtime_info')
  },

  confirm: async (message, options) => {
    const { confirm } = await import('@tauri-apps/plugin-dialog')
    return confirm(message, options)
  },

  discoveredNodes: async () => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<DesktopNode[]>('discovered_nodes')
  },

  refreshDiscovery: async () => {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('refresh_discovery')
  },

  addManualNode: async (address) => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<DesktopNode>('add_manual_node', { address })
  },

  addServerFallbackNodes: async (addresses) => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<DesktopNode[]>('add_server_fallback_nodes', { addresses })
  },

  desktopLogin: async (origin, apiBasePath, username, password) => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<AuthSession>('desktop_login', {
      origin,
      apiBasePath,
      username,
      password,
      deviceName: desktopDeviceName(),
    })
  },

  desktopRefresh: async (origin, apiBasePath) => {
    const { invoke } = await import('@tauri-apps/api/core')
    try {
      return await invoke<AuthSession>('desktop_refresh', {
        origin,
        apiBasePath,
        deviceName: desktopDeviceName(),
      })
    } catch {
      return null
    }
  },

  desktopLogout: async (origin, apiBasePath, accessToken) => {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('desktop_logout', { origin, apiBasePath, accessToken })
  },

  notify: async ({ title, body, target }) => {
    const {
      isPermissionGranted,
      requestPermission,
      sendNotification,
    } = await import('@tauri-apps/plugin-notification')
    let granted = await isPermissionGranted()
    if (!granted) granted = await requestPermission() === 'granted'
    if (!granted) return
    sendNotification({
      title,
      body,
      extra: target ? { lanchatTarget: JSON.stringify(target) } : undefined,
      autoCancel: true,
    })
  },

  autostartEnabled: async () => {
    const { isEnabled } = await import('@tauri-apps/plugin-autostart')
    return isEnabled()
  },

  setAutostart: async (enabled) => {
    const { disable, enable } = await import('@tauri-apps/plugin-autostart')
    if (enabled) await enable()
    else await disable()
  },

  checkForUpdate: async (install = false) => {
    const { check } = await import('@tauri-apps/plugin-updater')
    try {
      const update = await check()
      if (!update) return { status: 'UP_TO_DATE' }
      if (!install) {
        return {
          status: 'AVAILABLE',
          currentVersion: update.currentVersion,
          version: update.version,
          notes: update.body,
        }
      }
      await update.downloadAndInstall()
      const { relaunch } = await import('@tauri-apps/plugin-process')
      await relaunch()
      return { status: 'INSTALLED', version: update.version }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      if (/endpoint|pubkey|public key|configuration|configured/i.test(message)) {
        return { status: 'UNCONFIGURED' }
      }
      throw cause
    }
  },

  takePendingNavigation: async () => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<DesktopNavigationTarget | null>('take_pending_deep_link')
  },

  listenForNodes: async (listener) => {
    const { listen } = await import('@tauri-apps/api/event')
    return listen<DesktopNode[]>('desktop://nodes-changed', (event) => listener(event.payload))
  },

  listenForNavigation: async (listener) => {
    const { listen } = await import('@tauri-apps/api/event')
    const unlistenDeepLink = await listen<DesktopNavigationTarget>(
      'desktop://deep-link',
      (event) => listener(event.payload),
    )
    const { onAction } = await import('@tauri-apps/plugin-notification')
    const actionListener = await onAction((notification) => {
      const raw = notification.extra?.lanchatTarget
      if (typeof raw !== 'string') return
      void (async () => {
        const { invoke } = await import('@tauri-apps/api/core')
        await invoke('desktop_show').catch(() => undefined)
        try {
          listener(JSON.parse(raw) as DesktopNavigationTarget)
        } catch {
          // Ignore malformed notification metadata; it never becomes a URL or command.
        }
      })()
    })
    return () => {
      unlistenDeepLink()
      actionListener.unregister()
    }
  },
}

export const nativeBridge: NativeBridge = isTauriRuntime() ? tauriBridge : webBridge
