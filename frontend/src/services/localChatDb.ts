import type { ChatGroup, ChatMessage, Friend, OutboxEntry } from '../types'

const DATABASE_NAME = 'lanchat_local_v2'
const DATABASE_VERSION = 2
const OUTBOX_STORE = 'outbox'
const MESSAGE_STORE = 'messages'
const POSITION_STORE = 'positions'
const DIRECTORY_STORE = 'directory'

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
  savedAt: string
}

let databasePromise: Promise<IDBDatabase> | null = null

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(OUTBOX_STORE)) {
        const outbox = database.createObjectStore(OUTBOX_STORE, { keyPath: 'clientMsgId' })
        outbox.createIndex('byCreatedAt', 'createdAt')
      }
      if (!database.objectStoreNames.contains(MESSAGE_STORE)) {
        const messages = database.createObjectStore(MESSAGE_STORE, { keyPath: 'cacheKey' })
        messages.createIndex('byConversationOrder', ['conversationId', 'sortValue'])
        messages.createIndex('byClientMsgId', 'clientMsgId', { unique: false })
      }
      if (!database.objectStoreNames.contains(POSITION_STORE)) {
        database.createObjectStore(POSITION_STORE, { keyPath: 'conversationId' })
      }
      if (!database.objectStoreNames.contains(DIRECTORY_STORE)) {
        database.createObjectStore(DIRECTORY_STORE, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('无法打开本地消息数据库'))
  })
  return databasePromise
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
  transaction.objectStore(OUTBOX_STORE).put(entry)
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
      message,
    }
    store.put(record)
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
): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(DIRECTORY_STORE, 'readwrite')
  transaction.objectStore(DIRECTORY_STORE).put({
    key: 'current',
    friends: [...friends],
    groups: [...groups],
    savedAt: new Date().toISOString(),
  } satisfies ConversationDirectoryRecord)
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

export async function clearLocalChatDatabase(): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(
    [OUTBOX_STORE, MESSAGE_STORE, POSITION_STORE, DIRECTORY_STORE],
    'readwrite',
  )
  transaction.objectStore(OUTBOX_STORE).clear()
  transaction.objectStore(MESSAGE_STORE).clear()
  transaction.objectStore(POSITION_STORE).clear()
  transaction.objectStore(DIRECTORY_STORE).clear()
  await transactionDone(transaction)
}
