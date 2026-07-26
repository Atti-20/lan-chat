export type NavigationKind = 'node' | 'room' | 'conversation' | 'broadcast'

export interface NavigationTarget {
  kind: NavigationKind
  value: string
  nodeOrigin?: string | null
}

export interface NotificationActionLike {
  actionId?: unknown
  notification?: {
    id?: unknown
    extra?: unknown
  }
}

export interface NavigationDelivery {
  deliveryId: string
  target: NavigationTarget
}

export interface TrustedNavigationNode {
  nodeId: string
  origin: string
}

export type CapacitorNavigationRejectionReason =
  | 'INVALID_TARGET'
  | 'NO_SELECTED_NODE'
  | 'MISSING_NODE_ORIGIN'
  | 'NODE_ORIGIN_MISMATCH'
  | 'NODE_ID_MISMATCH'

export type CapacitorNavigationAuthorization =
  | { allowed: true; target: NavigationTarget }
  | { allowed: false; reason: CapacitorNavigationRejectionReason }

export const LOCAL_NOTIFICATION_ACTION_EVENT = 'localNotificationActionPerformed'

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

interface PendingRecord {
  storedAt: number
  target: NavigationTarget
}

const DEFAULT_PENDING_KEY = 'lanchat_native_navigation_v2'
const DEFAULT_PENDING_TTL_MS = 24 * 60 * 60 * 1_000

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

export function normalizeNavigationTarget(raw: unknown): NavigationTarget | null {
  if (!raw || typeof raw !== 'object') return null
  const target = raw as Partial<NavigationTarget>
  if (typeof target.value !== 'string'
    || !['node', 'room', 'conversation', 'broadcast'].includes(String(target.kind))) {
    return null
  }
  const kind = target.kind as NavigationKind
  const value = target.value.trim()
  if (kind === 'node') {
    const origin = normalizedOrigin(
      target.nodeOrigin || (value.includes('://') ? value : null),
    )
    if (origin) return { kind, value: origin, nodeOrigin: origin }
    return /^[a-z0-9_-]{3,64}$/.test(value) ? { kind, value } : null
  }

  const validators: Record<Exclude<NavigationKind, 'node'>, RegExp> = {
    room: /^[A-Za-z0-9_-]{3,64}$/,
    conversation: /^[A-Za-z0-9._:-]{1,200}$/,
    broadcast: /^[1-9]\d{0,18}$/,
  }
  if (!validators[kind].test(value)) return null
  const origin = target.nodeOrigin ? normalizedOrigin(target.nodeOrigin) : null
  if (target.nodeOrigin && !origin) return null
  return { kind, value, nodeOrigin: origin }
}

/**
 * Android notification intents enter through an exported launcher activity and
 * therefore are untrusted. They may navigate only inside the node the user has
 * already selected; they can never establish or switch node trust.
 */
export function authorizeCapacitorNotificationTarget(
  rawTarget: unknown,
  currentNode: TrustedNavigationNode | null,
): CapacitorNavigationAuthorization {
  const target = normalizeNavigationTarget(rawTarget)
  if (!target) return { allowed: false, reason: 'INVALID_TARGET' }
  if (!currentNode) return { allowed: false, reason: 'NO_SELECTED_NODE' }

  const currentOrigin = normalizedOrigin(currentNode.origin)
  if (!currentOrigin) return { allowed: false, reason: 'NO_SELECTED_NODE' }
  if (!target.nodeOrigin) return { allowed: false, reason: 'MISSING_NODE_ORIGIN' }
  if (target.nodeOrigin !== currentOrigin) {
    return { allowed: false, reason: 'NODE_ORIGIN_MISMATCH' }
  }

  if (target.kind === 'node') {
    const valueOrigin = normalizedOrigin(target.value)
    if (target.value !== currentNode.nodeId && valueOrigin !== currentOrigin) {
      return { allowed: false, reason: 'NODE_ID_MISMATCH' }
    }
  }
  return { allowed: true, target }
}

/**
 * A pending target may have been stored before a node switch; consuming it on a
 * different node would open an unrelated entity that happens to share the id.
 */
export function pendingTargetBelongsToNode(
  target: NavigationTarget,
  currentNode: TrustedNavigationNode | null,
): boolean {
  if (!target.nodeOrigin) return true
  const currentOrigin = currentNode ? normalizedOrigin(currentNode.origin) : null
  return Boolean(currentOrigin) && target.nodeOrigin === currentOrigin
}

export function parseNotificationNavigation(action: NotificationActionLike): NavigationDelivery | null {
  const notificationId = action.notification?.id
  if (typeof notificationId !== 'number' || !Number.isSafeInteger(notificationId)) return null
  const extra = action.notification?.extra
  if (!extra || typeof extra !== 'object') return null
  const rawTarget = (extra as Record<string, unknown>).lanchatTarget
  let decoded: unknown = rawTarget
  if (typeof rawTarget === 'string') {
    try {
      decoded = JSON.parse(rawTarget)
    } catch {
      return null
    }
  }
  const target = normalizeNavigationTarget(decoded)
  if (!target) return null
  const actionId = typeof action.actionId === 'string' && action.actionId.trim()
    ? action.actionId.trim().slice(0, 80)
    : 'tap'
  return { deliveryId: `${notificationId}:${actionId}`, target }
}

export class NavigationDeliveryDeduper {
  private readonly recent = new Map<string, number>()

  constructor(
    private readonly now: () => number = Date.now,
    private readonly ttlMs = 10 * 60 * 1_000,
    private readonly maxEntries = 128,
  ) {}

  accept(deliveryId: string): boolean {
    const now = this.now()
    for (const [id, seenAt] of this.recent) {
      if (now - seenAt > this.ttlMs) this.recent.delete(id)
    }
    if (!deliveryId || this.recent.has(deliveryId)) return false
    this.recent.set(deliveryId, now)
    while (this.recent.size > this.maxEntries) {
      const oldest = this.recent.keys().next().value as string | undefined
      if (!oldest) break
      this.recent.delete(oldest)
    }
    return true
  }
}

const MAX_ANDROID_NOTIFICATION_ID = 2_147_483_647

export class MonotonicNotificationIdAllocator {
  private lastId = 0

  constructor(
    private readonly now: () => number = Date.now,
    private readonly maximum = MAX_ANDROID_NOTIFICATION_ID,
  ) {}

  next(): number {
    const safeMaximum = Math.max(2, Math.trunc(this.maximum))
    const now = Math.max(0, Math.trunc(this.now()))
    const timeCandidate = (now % safeMaximum) || 1
    if (this.lastId === 0 || timeCandidate > this.lastId) {
      this.lastId = timeCandidate
    } else {
      this.lastId = this.lastId >= safeMaximum ? 1 : this.lastId + 1
    }
    return this.lastId
  }
}

export function createNotificationNavigationHandler(
  listener: (target: NavigationTarget) => void,
  deduper: NavigationDeliveryDeduper = new NavigationDeliveryDeduper(),
): (action: NotificationActionLike) => void {
  return (action) => {
    const delivery = parseNotificationNavigation(action)
    if (!delivery || !deduper.accept(delivery.deliveryId)) return
    listener(delivery.target)
  }
}

export class PendingNavigationStore {
  constructor(
    private readonly storage: StorageLike,
    private readonly key = DEFAULT_PENDING_KEY,
    private readonly now: () => number = Date.now,
    private readonly ttlMs = DEFAULT_PENDING_TTL_MS,
  ) {}

  pending(): NavigationTarget | null {
    try {
      const raw = this.storage.getItem(this.key)
      if (!raw) return null
      const parsed = JSON.parse(raw) as Partial<PendingRecord>
      if (typeof parsed.storedAt !== 'number' || this.now() - parsed.storedAt > this.ttlMs) {
        this.storage.removeItem(this.key)
        return null
      }
      const target = normalizeNavigationTarget(parsed.target)
      if (!target) this.storage.removeItem(this.key)
      return target
    } catch {
      this.storage.removeItem(this.key)
      return null
    }
  }

  store(rawTarget: unknown): NavigationTarget | null {
    const target = normalizeNavigationTarget(rawTarget)
    if (!target) return null
    const record: PendingRecord = { storedAt: this.now(), target }
    this.storage.setItem(this.key, JSON.stringify(record))
    return target
  }

  claim(expected?: NavigationTarget): NavigationTarget | null {
    const target = this.pending()
    if (!target || (expected && !sameTarget(target, expected))) return null
    this.storage.removeItem(this.key)
    return target
  }
}

function sameTarget(first: NavigationTarget, second: NavigationTarget): boolean {
  return first.kind === second.kind
    && first.value === second.value
    && (first.nodeOrigin || null) === (second.nodeOrigin || null)
}
