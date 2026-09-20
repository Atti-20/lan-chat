export type RuntimeKind = 'web' | 'tauri' | 'capacitor'

export type NavigationKind = 'node' | 'room' | 'conversation' | 'broadcast'

export interface NavigationTarget {
  kind: NavigationKind
  value: string
  nodeOrigin?: string | null
  messageId?: string
  notification?: boolean
  notificationOwner?: string
}
