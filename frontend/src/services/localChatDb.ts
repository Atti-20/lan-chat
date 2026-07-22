import { toRaw } from 'vue'
import type { ChatGroup, ChatMessage, DirectFileRecord, Friend, OutboxEntry, TemporaryRoom } from '../types'

const DATABASE_NAME = 'lanchat_local_v2'
const DATABASE_VERSION = 5
const OUTBOX_STORE = 'outbox'
const MESSAGE_STORE = 'messages'
const POSITION_STORE = 'positions'
const DIRECTORY_STORE = 'directory'
const DIRECT_FILE_STORE = 'directFiles'
const AVATAR_IMAGE_STORE = 'avatarImages'
const MAX_AVATAR_IMAGE_COUNT = 80
const MAX_AVATAR_IMAGE_BYTES = 40 * 1024 * 1024

interface CachedMessageRecord {
  cacheKey: string
  conversationId: string
  clientMsgId?: string
  sortValue: number
  message: ChatMessage
}

interface PositionRecord {
  conversationId: string
  sequence: number
}

interface ConversationDirectoryRecord {
  key: 'current'
  friends: Friend[]
  groups: ChatGroup[]
  rooms?: TemporaryRoom[]
  savedAt: string
}

interface CachedAvatarImageRecord {
  cacheKey: string
  blob: Blob
  size: number
  savedAt: number
}

export interface CachedAvatarImage {
  blob: Blob
  savedAt: number
}

let databasePromise: Promise<IDBDatabase> | null = null

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise
  if (typeof indexedDB === 'undefined') {
    return Promise.reject(new Error('当前浏览器不支持本地消息存储'))
  }
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      const upgradeTransaction = request.transaction
      if (!database.objectStoreNames.contains(OUTBOX_STORE)) {
        const outbox = database.createObjectStore(OUTBOX_STORE, { keyPath: 'clientMsgId' })
        outbox.createIndex('byCreatedAt', 'createdAt')
      } else {
        const outbox = upgradeTransaction!.objectStore(OUTBOX_STORE)
        if (!outbox.indexNames.contains('byCreatedAt')) {
          outbox.createIndex('byCreatedAt', 'createdAt')
        }
      }
      if (!database.objectStoreNames.contains(MESSAGE_STORE)) {
        const messages = database.createObjectStore(MESSAGE_STORE, { keyPath: 'cacheKey' })
        messages.createIndex('byConversationOrder', ['conversationId', 'sortValue'])
        messages.createIndex('byClientMsgId', 'clientMsgId', { unique: false })
      } else {
        const messages = upgradeTransaction!.objectStore(MESSAGE_STORE)
        if (!messages.indexNames.contains('byConversationOrder')) {
          messages.createIndex('byConversationOrder', ['conversationId', 'sortValue'])
        }
        if (!messages.indexNames.contains('byClientMsgId')) {
          messages.createIndex('byClientMsgId', 'clientMsgId', { unique: false })
        }
      }
      if (!database.objectStoreNames.contains(POSITION_STORE)) {
        database.createObjectStore(POSITION_STORE, { keyPath: 'conversationId' })
      }
      if (!database.objectStoreNames.contains(DIRECTORY_STORE)) {
        database.createObjectStore(DIRECTORY_STORE, { keyPath: 'key' })
      }
      if (!database.objectStoreNames.contains(DIRECT_FILE_STORE)) {
        const files = database.createObjectStore(DIRECT_FILE_STORE, { keyPath: 'transferId' })
        files.createIndex('bySavedAt', 'savedAt')
      }
      if (!database.objectStoreNames.contains(AVATAR_IMAGE_STORE)) {
        const avatars = database.createObjectStore(AVATAR_IMAGE_STORE, { keyPath: 'cacheKey' })
        avatars.createIndex('bySavedAt', 'savedAt')
      }
    }
    request.onsuccess = () => {
      const database = request.result
      database.onversionchange = () => {
        database.close()
        databasePromise = null
      }
      resolve(database)
    }
    request.onerror = () => {
      databasePromise = null
      reject(request.error || new Error('无法打开本地消息数据库'))
    }
  })
  return databasePromise
}

function unwrapStorageValue(value: unknown, seen = new WeakMap<object, unknown>()): unknown {
  const raw = toRaw(value)
  if (raw === null || typeof raw !== 'object') return raw
  if (raw instanceof Date) return new Date(raw.getTime())
  if (seen.has(raw)) return seen.get(raw)

  if (Array.isArray(raw)) {
    const result: unknown[] = []
    seen.set(raw, result)
    raw.forEach((item) => result.push(unwrapStorageValue(item, seen)))
    return result
  }

  const result: Record<string, unknown> = {}
  seen.set(raw, result)
  Object.entries(raw).forEach(([key, item]) => {
    result[key] = unwrapStorageValue(item, seen)
  })
  return result
}

function storageSnapshot<T>(value: T): T {
  const raw = unwrapStorageValue(value)
  if (typeof structuredClone === 'function') {
    try {
      return structuredClone(raw) as T
    } catch {
      // Older WebKit versions can still reject an object containing a nested
      // proxy; the JSON fallback keeps the local cache usable for message data.
    }
  }
  return JSON.parse(JSON.stringify(raw)) as T
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error || new Error('本地数据事务已取消'))
    transaction.onerror = () => reject(transaction.error || new Error('本地数据事务失败'))
  })
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('本地数据读取失败'))
  })
}

export async function loadOutbox(): Promise<OutboxEntry[]> {
  const database = await openDatabase()
  const transaction = database.transaction(OUTBOX_STORE, 'readonly')
  const request = transaction.objectStore(OUTBOX_STORE).index('byCreatedAt').getAll()
  const result = await requestResult(request) as OutboxEntry[]
  await transactionDone(transaction)
  return result.sort((first, second) => first.createdAt.localeCompare(second.createdAt))
}

export async function saveOutboxEntry(entry: OutboxEntry): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(OUTBOX_STORE, 'readwrite')
  transaction.objectStore(OUTBOX_STORE).put(storageSnapshot(entry))
  await transactionDone(transaction)
}

export async function deleteOutboxEntry(clientMsgId: string): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(OUTBOX_STORE, 'readwrite')
  transaction.objectStore(OUTBOX_STORE).delete(clientMsgId)
  await transactionDone(transaction)
}

export async function cacheMessages(messages: readonly ChatMessage[]): Promise<void> {
  const cacheable = messages.filter((message) => Boolean(message.conversationId))
  if (cacheable.length === 0) return
  const database = await openDatabase()
  const transaction = database.transaction(MESSAGE_STORE, 'readwrite')
  const store = transaction.objectStore(MESSAGE_STORE)
  cacheable.forEach((message) => {
    const conversationId = message.conversationId!
    const identity = message.messageId || `client:${message.clientMsgId}`
    const parsedTime = Date.parse(message.clientCreatedAt || message.createTime || '')
    const sortValue = message.sequence ?? (Number.isFinite(parsedTime) ? parsedTime : Date.now())
    const record: CachedMessageRecord = {
      cacheKey: `${conversationId}:${identity}`,
      conversationId,
      clientMsgId: message.clientMsgId,
      sortValue: Number.isFinite(sortValue) ? sortValue : Date.now(),
      message: storageSnapshot(message),
    }
    store.put(storageSnapshot(record))
  })
  await transactionDone(transaction)
}

export async function deleteCachedMessagesByClientMsgId(clientMsgId: string): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(MESSAGE_STORE, 'readwrite')
  const index = transaction.objectStore(MESSAGE_STORE).index('byClientMsgId')
  const request = index.openKeyCursor(IDBKeyRange.only(clientMsgId))
  request.onsuccess = () => {
    const cursor = request.result
    if (!cursor) return
    transaction.objectStore(MESSAGE_STORE).delete(cursor.primaryKey)
    cursor.continue()
  }
  await transactionDone(transaction)
}

export async function loadCachedMessages(
  conversationId: string,
  limit = 100,
): Promise<ChatMessage[]> {
  const database = await openDatabase()
  const transaction = database.transaction(MESSAGE_STORE, 'readonly')
  const index = transaction.objectStore(MESSAGE_STORE).index('byConversationOrder')
  const range = IDBKeyRange.bound(
    [conversationId, 0],
    [conversationId, Number.MAX_SAFE_INTEGER],
  )
  const messages: ChatMessage[] = []

  await new Promise<void>((resolve, reject) => {
    const request = index.openCursor(range, 'prev')
    request.onsuccess = () => {
      const cursor = request.result
      if (!cursor || messages.length >= limit) {
        resolve()
        return
      }
      messages.push((cursor.value as CachedMessageRecord).message)
      cursor.continue()
    }
    request.onerror = () => reject(request.error || new Error('本地消息读取失败'))
  })
  await transactionDone(transaction)
  return messages.reverse()
}

export async function savePosition(conversationId: string, sequence: number): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(POSITION_STORE, 'readwrite')
  const store = transaction.objectStore(POSITION_STORE)
  const existing = await requestResult(store.get(conversationId)) as PositionRecord | undefined
  store.put({
    conversationId,
    sequence: Math.max(existing?.sequence || 0, sequence),
  } satisfies PositionRecord)
  await transactionDone(transaction)
}

export async function loadPositions(): Promise<Record<string, number>> {
  const database = await openDatabase()
  const transaction = database.transaction(POSITION_STORE, 'readonly')
  const records = await requestResult(transaction.objectStore(POSITION_STORE).getAll()) as PositionRecord[]
  await transactionDone(transaction)
  return Object.fromEntries(records.map((record) => [record.conversationId, record.sequence]))
}

export async function saveConversationDirectory(
  friends: readonly Friend[],
  groups: readonly ChatGroup[],
  rooms: readonly TemporaryRoom[] = [],
): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(DIRECTORY_STORE, 'readwrite')
  transaction.objectStore(DIRECTORY_STORE).put(storageSnapshot({
    key: 'current',
    friends: [...friends],
    groups: [...groups],
    rooms: [...rooms],
    savedAt: new Date().toISOString(),
  } satisfies ConversationDirectoryRecord))
  await transactionDone(transaction)
}

export async function loadConversationDirectory(): Promise<ConversationDirectoryRecord | null> {
  const database = await openDatabase()
  const transaction = database.transaction(DIRECTORY_STORE, 'readonly')
  const record = await requestResult(
    transaction.objectStore(DIRECTORY_STORE).get('current'),
  ) as ConversationDirectoryRecord | undefined
  await transactionDone(transaction)
  return record || null
}

export async function loadCachedAvatarImage(cacheKey: string): Promise<CachedAvatarImage | null> {
  if (!cacheKey) return null
  const database = await openDatabase()
  const transaction = database.transaction(AVATAR_IMAGE_STORE, 'readonly')
  const record = await requestResult(
    transaction.objectStore(AVATAR_IMAGE_STORE).get(cacheKey),
  ) as CachedAvatarImageRecord | undefined
  await transactionDone(transaction)
  if (!record || !(record.blob instanceof Blob)) return null
  return {
    blob: record.blob,
    savedAt: record.savedAt,
  }
}

export async function cacheAvatarImage(cacheKey: string, blob: Blob): Promise<void> {
  if (!cacheKey || blob.size <= 0 || blob.size > MAX_AVATAR_IMAGE_BYTES) return
  const database = await openDatabase()
  const transaction = database.transaction(AVATAR_IMAGE_STORE, 'readwrite')
  const store = transaction.objectStore(AVATAR_IMAGE_STORE)
  const existing = await requestResult(store.getAll()) as CachedAvatarImageRecord[]
  const previous = existing.find((record) => record.cacheKey === cacheKey)
  let totalBytes = existing.reduce((total, record) => total + record.size, 0) - (previous?.size || 0)
  const retained = existing
    .filter((record) => record.cacheKey !== cacheKey)
    .sort((first, second) => first.savedAt - second.savedAt)

  while (
    retained.length >= MAX_AVATAR_IMAGE_COUNT
    || totalBytes + blob.size > MAX_AVATAR_IMAGE_BYTES
  ) {
    const oldest = retained.shift()
    if (!oldest) break
    totalBytes -= oldest.size
    store.delete(oldest.cacheKey)
  }

  store.put({
    cacheKey,
    blob,
    size: blob.size,
    savedAt: Date.now(),
  } satisfies CachedAvatarImageRecord)
  await transactionDone(transaction)
}

export async function deleteCachedAvatarImage(cacheKey: string): Promise<void> {
  if (!cacheKey) return
  const database = await openDatabase()
  const transaction = database.transaction(AVATAR_IMAGE_STORE, 'readwrite')
  transaction.objectStore(AVATAR_IMAGE_STORE).delete(cacheKey)
  await transactionDone(transaction)
}

export async function saveDirectFile(record: DirectFileRecord): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(DIRECT_FILE_STORE, 'readwrite')
  // Blob is natively structured-cloneable by IndexedDB. Do not pass it through
  // storageSnapshot, whose plain-object fallback is intended for JSON message data.
  transaction.objectStore(DIRECT_FILE_STORE).put({ ...record })
  await transactionDone(transaction)
}

export async function loadDirectFile(transferId: string): Promise<DirectFileRecord | null> {
  if (!transferId) return null
  const database = await openDatabase()
  const transaction = database.transaction(DIRECT_FILE_STORE, 'readonly')
  const record = await requestResult(
    transaction.objectStore(DIRECT_FILE_STORE).get(transferId),
  ) as DirectFileRecord | undefined
  await transactionDone(transaction)
  return record || null
}

export async function moveDirectFile(oldTransferId: string, newTransferId: string): Promise<void> {
  if (!oldTransferId || !newTransferId || oldTransferId === newTransferId) return
  const database = await openDatabase()
  const transaction = database.transaction(DIRECT_FILE_STORE, 'readwrite')
  const store = transaction.objectStore(DIRECT_FILE_STORE)
  const record = await requestResult(store.get(oldTransferId)) as DirectFileRecord | undefined
  if (record) {
    store.put({ ...record, transferId: newTransferId })
    store.delete(oldTransferId)
  }
  await transactionDone(transaction)
}

export async function deleteDirectFile(transferId: string): Promise<void> {
  if (!transferId) return
  const database = await openDatabase()
  const transaction = database.transaction(DIRECT_FILE_STORE, 'readwrite')
  transaction.objectStore(DIRECT_FILE_STORE).delete(transferId)
  await transactionDone(transaction)
}

export async function clearLocalChatDatabase(): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(
    [OUTBOX_STORE, MESSAGE_STORE, POSITION_STORE, DIRECTORY_STORE, DIRECT_FILE_STORE, AVATAR_IMAGE_STORE],
    'readwrite',
  )
  transaction.objectStore(OUTBOX_STORE).clear()
  transaction.objectStore(MESSAGE_STORE).clear()
  transaction.objectStore(POSITION_STORE).clear()
  transaction.objectStore(DIRECTORY_STORE).clear()
  transaction.objectStore(DIRECT_FILE_STORE).clear()
  transaction.objectStore(AVATAR_IMAGE_STORE).clear()
  await transactionDone(transaction)
}
