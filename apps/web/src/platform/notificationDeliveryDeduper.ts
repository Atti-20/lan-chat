export interface NotificationDeliveryStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

interface NotificationDeliveryRecord {
  key: string
  deliveredAt: number
}

const STORAGE_KEY = 'meshx_notification_delivery_v1'
const DEFAULT_TTL_MS = 30 * 24 * 60 * 60 * 1_000
const DEFAULT_MAX_ENTRIES = 512

function browserStorage(): NotificationDeliveryStorage | null {
  try {
    if (typeof window === 'undefined') return null
    return window.localStorage
  } catch {
    return null
  }
}

/**
 * Keeps business-message notifications idempotent across WebSocket reconnects
 * and process restarts.  Only opaque message identifiers are persisted; titles
 * and message bodies never enter storage.
 */
export class NotificationDeliveryDeduper {
  private fallback: NotificationDeliveryRecord[] = []

  constructor(
    private readonly storageProvider: () => NotificationDeliveryStorage | null = browserStorage,
    private readonly now: () => number = Date.now,
    private readonly ttlMs = DEFAULT_TTL_MS,
    private readonly maxEntries = DEFAULT_MAX_ENTRIES,
    private readonly storageKey = STORAGE_KEY,
  ) {}

  accept(rawKey?: string): boolean {
    const key = normalizeKey(rawKey)
    if (!key) return true

    const now = this.now()
    const records = this.read().filter((record) => isCurrent(record, now, this.ttlMs))
    if (records.some((record) => record.key === key)) {
      this.write(records)
      return false
    }

    records.push({ key, deliveredAt: now })
    while (records.length > this.maxEntries) records.shift()
    this.write(records)
    return true
  }

  forget(rawKey?: string): void {
    const key = normalizeKey(rawKey)
    if (!key) return
    const records = this.read().filter((record) => record.key !== key)
    this.write(records)
  }

  private read(): NotificationDeliveryRecord[] {
    const storage = this.storageProvider()
    if (!storage) return [...this.fallback]
    try {
      const raw = storage.getItem(this.storageKey)
      if (!raw) return [...this.fallback]
      const parsed = JSON.parse(raw) as unknown
      if (!Array.isArray(parsed)) return [...this.fallback]
      const stored = parsed.flatMap((item) => {
        if (!item || typeof item !== 'object') return []
        const record = item as Partial<NotificationDeliveryRecord>
        const key = normalizeKey(record.key)
        const deliveredAt = record.deliveredAt
        if (!key || typeof deliveredAt !== 'number' || !Number.isSafeInteger(deliveredAt)) return []
        return [{ key, deliveredAt }]
      })
      return mergeRecords(stored, this.fallback)
    } catch {
      return [...this.fallback]
    }
  }

  private write(records: readonly NotificationDeliveryRecord[]): void {
    const snapshot = records.slice(-this.maxEntries)
    const storage = this.storageProvider()
    if (!storage) {
      this.fallback = [...snapshot]
      return
    }
    try {
      storage.setItem(this.storageKey, JSON.stringify(snapshot))
      this.fallback = [...snapshot]
    } catch {
      this.fallback = [...snapshot]
    }
  }
}

function normalizeKey(value?: string): string | null {
  if (typeof value !== 'string') return null
  const key = value.trim()
  return key && key.length <= 480 ? key : null
}

function isCurrent(
  record: NotificationDeliveryRecord,
  now: number,
  ttlMs: number,
): boolean {
  return now >= record.deliveredAt && now - record.deliveredAt <= ttlMs
}

function mergeRecords(
  stored: readonly NotificationDeliveryRecord[],
  fallback: readonly NotificationDeliveryRecord[],
): NotificationDeliveryRecord[] {
  const merged = new Map<string, NotificationDeliveryRecord>()
  ;[...stored, ...fallback].forEach((record) => {
    const previous = merged.get(record.key)
    if (!previous || record.deliveredAt > previous.deliveredAt) merged.set(record.key, record)
  })
  return [...merged.values()].sort((first, second) => first.deliveredAt - second.deliveredAt)
}
