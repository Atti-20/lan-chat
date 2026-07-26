import type { AuthSession, DiscoveredNode } from '../types'
import { registerPlugin } from '@capacitor/core'
import { capacitorPlatform, isCapacitorRuntime } from './mobileRuntime'
import { verifyMobileNode } from './mobileNodeVerification'
import {
  createNotificationNavigationHandler,
  LOCAL_NOTIFICATION_ACTION_EVENT,
  MonotonicNotificationIdAllocator,
  NavigationDeliveryDeduper,
  type NavigationKind,
  type NavigationTarget,
} from './navigationTarget'

export type RuntimeKind = 'web' | 'tauri' | 'capacitor'
export type DesktopPlatform = 'web' | 'macos' | 'windows' | 'linux' | 'android' | 'ios' | 'unknown'
export type DesktopNodeSource = 'MDNS' | 'SERVER_FALLBACK' | 'CACHE' | 'MANUAL'
export type DesktopNodeHealth = 'UNKNOWN' | 'PROBING' | 'HEALTHY' | 'DEGRADED' | 'OFFLINE'
export type DesktopNavigationKind = NavigationKind

export interface RuntimeInfo {
  runtime: RuntimeKind
  platform: DesktopPlatform
  version: string
  updaterConfigured?: boolean
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

export type DesktopNavigationTarget = NavigationTarget

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

interface MeshXDiscoveryPlugin {
  scan(): Promise<{ origins?: unknown }>
}

const meshXDiscovery = registerPlugin<MeshXDiscoveryPlugin>('MeshXDiscovery')

interface MeshXFilesPlugin {
  save(options: { url: string; name: string; mimeType?: string }): Promise<{
    cancelled?: unknown
    location?: unknown
  }>
}

const meshXFiles = registerPlugin<MeshXFilesPlugin>('MeshXFiles')

interface MeshXAuthPlugin {
  login(options: {
    origin: string
    apiBasePath: string
    username: string
    password: string
    deviceName: string
  }): Promise<AuthSession>
  refresh(options: {
    origin: string
    apiBasePath: string
    deviceName: string
  }): Promise<AuthSession>
  logout(options: {
    origin: string
    apiBasePath: string
    accessToken?: string
  }): Promise<void>
  clearNodeSession(options: { origin: string }): Promise<void>
}

const meshXAuth = registerPlugin<MeshXAuthPlugin>('MeshXAuth')
const mobileNavigationDeduper = new NavigationDeliveryDeduper()
const mobileNotificationIds = new MonotonicNotificationIdAllocator()

export interface NativeFileSaveResult {
  location: string
}

export interface NativeFileSaveProgress {
  writtenBytes: number
  totalBytes?: number | null
}

export interface NativeFileSaveOptions {
  expectedBytes?: number
  sha256?: string
  signal?: AbortSignal
  onProgress?: (progress: NativeFileSaveProgress) => void
}

export interface NativeBridge {
  runtime(): RuntimeKind
  runtimeInfo(): Promise<RuntimeInfo>
  confirm(message: string, options?: ConfirmOptions): Promise<boolean>
  discoveredNodes(): Promise<DesktopNode[]>
  discoverNodeOrigins(): Promise<string[]>
  refreshDiscovery(): Promise<void>
  addManualNode(address: string): Promise<DesktopNode>
  addServerFallbackNodes(addresses: string[]): Promise<DesktopNode[]>
  nativeLogin(
    origin: string,
    apiBasePath: string,
    username: string,
    password: string,
  ): Promise<AuthSession>
  nativeRefresh(origin: string, apiBasePath: string): Promise<AuthSession | null>
  nativeLogout(origin: string, apiBasePath: string, accessToken?: string): Promise<void>
  clearNodeSession(origin: string): Promise<void>
  saveFile(
    url: string,
    name: string,
    mimeType?: string,
    options?: NativeFileSaveOptions,
  ): Promise<NativeFileSaveResult | null>
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
  return `MeshX Desktop (${platform})`.slice(0, 100)
}

function mobileDeviceName(): string {
  const platform = capacitorPlatform()
  const label = platform === 'android' ? 'Android' : platform === 'ios' ? 'iOS' : 'Mobile'
  return `MeshX ${label} (${navigator.userAgent})`.slice(0, 100)
}

const webBridge: NativeBridge = {
  runtime: () => 'web',

  runtimeInfo: async () => ({
    runtime: 'web',
    platform: 'web',
    version: import.meta.env.VITE_APP_VERSION || 'web',
    updaterConfigured: false,
  }),

  confirm: async (message, options) => window.confirm(
    options?.title ? `${options.title}\n\n${message}` : message,
  ),
  discoveredNodes: async () => {
    const response = await meshXDiscovery.scan()
    if (!Array.isArray(response.origins)) return []
    const candidates = response.origins.filter(
      (origin): origin is string => typeof origin === 'string',
    )
    const verified = await Promise.allSettled(candidates.map(verifyMobileNode))
    return verified.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])
  },
  discoverNodeOrigins: async () => [],
  refreshDiscovery: async () => undefined,
  addManualNode: async () => {
    throw new Error('网页模式不支持原生节点验证')
  },
  addServerFallbackNodes: async () => [],
  nativeLogin: async () => {
    throw new Error('网页模式不支持原生认证')
  },
  nativeRefresh: async () => null,
  nativeLogout: async () => undefined,
  clearNodeSession: async () => undefined,
  saveFile: async () => null,

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
  // Capacitor LocalNotifications retains a cold-start action until this first
  // listener is registered. installDesktopNavigation deliberately subscribes
  // before asking takePendingNavigation, so Android has no second pull source.
  takePendingNavigation: async () => null,
  listenForNodes: async () => () => undefined,
  listenForNavigation: async () => () => undefined,
}

const capacitorBridge: NativeBridge = {
  runtime: () => 'capacitor',

  runtimeInfo: async () => ({
    runtime: 'capacitor',
    platform: capacitorPlatform(),
    version: import.meta.env.VITE_APP_VERSION || 'mobile',
    updaterConfigured: false,
  }),

  confirm: async (message, options) => window.confirm(
    options?.title ? `${options.title}\n\n${message}` : message,
  ),
  discoveredNodes: async () => [],
  discoverNodeOrigins: async () => {
    const response = await meshXDiscovery.scan()
    return Array.isArray(response.origins)
      ? response.origins.filter((origin): origin is string => typeof origin === 'string')
      : []
  },
  refreshDiscovery: async () => undefined,
  addManualNode: verifyMobileNode,
  addServerFallbackNodes: async () => [],
  nativeLogin: async (origin, apiBasePath, username, password) => {
    return meshXAuth.login({
      origin,
      apiBasePath,
      username,
      password,
      deviceName: mobileDeviceName(),
    })
  },
  nativeRefresh: async (origin, apiBasePath) => {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        return await meshXAuth.refresh({
          origin,
          apiBasePath,
          deviceName: mobileDeviceName(),
        })
      } catch (cause) {
        // AUTH_BUSY 表示另一原生认证调用在途，并不代表会话失效；
        // 稍候重试一次，避免把互斥误判成登录过期。
        if ((cause as { code?: string } | null)?.code !== 'AUTH_BUSY' || attempt > 0) return null
        await new Promise((resolve) => window.setTimeout(resolve, 350))
      }
    }
    return null
  },
  nativeLogout: async (origin, apiBasePath, accessToken) => {
    await meshXAuth.logout({ origin, apiBasePath, accessToken })
  },
  clearNodeSession: async (origin) => {
    await meshXAuth.clearNodeSession({ origin })
  },

  saveFile: async (url, name, mimeType) => {
    const result = await meshXFiles.save({ url, name, mimeType })
    if (result.cancelled === true) return null
    return {
      // Android storage providers intentionally expose a display name instead
      // of a filesystem path under scoped storage.
      location: typeof result.location === 'string' ? result.location : '所选位置',
    }
  },

  notify: async ({ title, body, target }) => {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    let permission = await LocalNotifications.checkPermissions()
    if (permission.display === 'prompt') permission = await LocalNotifications.requestPermissions()
    if (permission.display !== 'granted') return
    await LocalNotifications.schedule({
      notifications: [{
        id: mobileNotificationIds.next(),
        title,
        body,
        extra: target ? { lanchatTarget: JSON.stringify(target) } : undefined,
      }],
    })
  },

  autostartEnabled: async () => false,
  setAutostart: async () => {
    throw new Error('Android 端不支持开机自启设置')
  },
  checkForUpdate: async () => ({ status: 'UNSUPPORTED' }),
  takePendingNavigation: async () => null,
  listenForNodes: async () => () => undefined,
  listenForNavigation: async (listener) => {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    const handle = await LocalNotifications.addListener(
      LOCAL_NOTIFICATION_ACTION_EVENT,
      createNotificationNavigationHandler(listener, mobileNavigationDeduper),
    )
    return () => { void handle.remove() }
  },
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

  discoverNodeOrigins: async () => [],

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

  nativeLogin: async (origin, apiBasePath, username, password) => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<AuthSession>('desktop_login', {
      origin,
      apiBasePath,
      username,
      password,
      deviceName: desktopDeviceName(),
    })
  },

  nativeRefresh: async (origin, apiBasePath) => {
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

  nativeLogout: async (origin, apiBasePath, accessToken) => {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('desktop_logout', { origin, apiBasePath, accessToken })
  },

  clearNodeSession: async (origin) => {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('desktop_logout', { origin })
  },

  saveFile: async (url, name, _mimeType, options) => {
    if (options?.signal?.aborted) return null
    const { save } = await import('@tauri-apps/plugin-dialog')
    const path = await save({ defaultPath: name })
    if (!path) return null
    if (options?.signal?.aborted) return null

    const { Channel, invoke } = await import('@tauri-apps/api/core')
    const downloadId = `attachment_${crypto.randomUUID().replace(/-/g, '')}`
    const progress = new Channel<{
      downloadId: string
      writtenBytes: number
      totalBytes?: number | null
    }>((event) => {
      if (event.downloadId !== downloadId) return
      options?.onProgress?.({
        writtenBytes: event.writtenBytes,
        totalBytes: event.totalBytes,
      })
    })
    const cancel = () => {
      void invoke('cancel_attachment', { downloadId }).catch(() => undefined)
    }
    options?.signal?.addEventListener('abort', cancel, { once: true })
    try {
      await invoke('save_attachment', {
        request: {
          downloadId,
          origin: new URL(url).origin,
          url,
          path,
          expectedBytes: options?.expectedBytes,
          sha256: options?.sha256,
        },
        onProgress: progress,
      })
      if (options?.signal?.aborted) return null
      return { location: path }
    } catch (cause) {
      if (options?.signal?.aborted || String(cause).includes('ATTACHMENT_CANCELLED')) return null
      throw cause
    } finally {
      options?.signal?.removeEventListener('abort', cancel)
    }
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
    const { invoke } = await import('@tauri-apps/api/core')
    if (!await invoke<boolean>('updater_configured')) return { status: 'UNCONFIGURED' }
    const { check } = await import('@tauri-apps/plugin-updater')
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
      (event) => {
        void (async () => {
          // A live event is also retained by Rust for cold-start recovery.
          // Acknowledge that retained copy before dispatching it to the UI so
          // reopening the WebView cannot consume the same target twice.
          const { invoke } = await import('@tauri-apps/api/core')
          await invoke('take_pending_deep_link').catch(() => null)
          listener(event.payload)
        })()
      },
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

export const nativeBridge: NativeBridge = isTauriRuntime()
  ? tauriBridge
  : isCapacitorRuntime()
    ? capacitorBridge
    : webBridge
