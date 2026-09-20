import type { AuthSession, DiscoveredNode, NodeRuntimeStatus } from '../../domain-ts/src/models'
import type { NavigationKind, NavigationTarget, RuntimeKind } from './runtime'
export type { RuntimeKind } from './runtime'

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
  organizationId?: string | null
  apiOrigin?: string
  apiBasePath?: string
  webSocketPath?: string
  healthPath?: string
  appPath?: string
  lastSuccessfulAt?: string | null
  deviceIdentityEnabled?: boolean
  controlSigningPublicKey?: string | null
  controlSigningKeyFingerprint?: string | null
}

export type DesktopNavigationTarget = NavigationTarget

export interface RecoveryNotificationRequest {
  owner: string
  kind: 'OWNER' | 'SHOW' | 'CANCEL' | 'CANCEL_ALL'
  id?: string
  target?: DesktopNavigationTarget
}

export interface NotificationInput {
  title: string
  body: string
  target?: DesktopNavigationTarget
  /** Stable business key used to suppress a reconnect retry of the same alert. */
  dedupeKey?: string
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
  nodeRuntimeStatus(): Promise<NodeRuntimeStatus>
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
  notificationRecovery(input: RecoveryNotificationRequest): Promise<void>
  notify(input: NotificationInput): Promise<void>
  autostartEnabled(): Promise<boolean>
  setAutostart(enabled: boolean): Promise<void>
  checkForUpdate(install?: boolean): Promise<UpdateResult>
  takePendingNavigation(): Promise<DesktopNavigationTarget | null>
  listenForNodes(listener: (nodes: DesktopNode[]) => void): Promise<() => void>
  listenForNavigation(listener: (target: DesktopNavigationTarget) => void): Promise<() => void>
}
