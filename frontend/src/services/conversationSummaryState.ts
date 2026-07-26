import type { ConversationKind, ConversationSummary } from '../types'

export type ConversationSummaryMap = Readonly<Record<string, ConversationSummary>>

export interface ConversationMessageDelta {
  conversationId: string
  sequence?: number
  fromUserId: number
  currentUserId: number
  content?: string
  contentType?: string
  createdAt?: string
  selected: boolean
  forcePreview?: boolean
}

export interface ConversationReadDelta {
  conversationId: string
  readerId: number
  currentUserId: number
  lastSequence: number
  lastReadSequence: number
  unreadCount: number
}

export function indexConversationSummaries(
  source: readonly ConversationSummary[],
): ConversationSummaryMap {
  const indexed: Record<string, ConversationSummary> = {}
  source.forEach((raw) => {
    const summary = normalizeConversationSummary(raw)
    if (summary) indexed[summary.conversationId] = summary
  })
  return indexed
}

export function applyConversationMessage(
  source: ConversationSummaryMap,
  delta: ConversationMessageDelta,
): ConversationSummaryMap {
  if (!delta.conversationId) return source
  const current = source[delta.conversationId]
    || emptyConversationSummary(delta.conversationId, delta.currentUserId)
  if (!current) return source

  const sequence = positiveSafeInteger(delta.sequence) ? delta.sequence : null
  const advancesSequence = sequence !== null && sequence > current.lastSequence
  const optimisticPreview = sequence === null
  // 焚毁/撤回（forcePreview）只在目标消息仍是当前预览时覆盖，
  // 否则处理旧消息会把较新的预览和 lastMessageAt 回退。
  const refreshesPreview = delta.forcePreview === true
    && (sequence === null || sequence >= current.lastSequence)
  const updatesPreview = refreshesPreview || optimisticPreview || advancesSequence
  const incoming = delta.fromUserId !== delta.currentUserId
  const shouldIncrementUnread = advancesSequence
    && incoming
    && !delta.selected
    && sequence! > current.lastReadSequence

  const next: ConversationSummary = {
    ...current,
    lastSequence: advancesSequence ? sequence! : current.lastSequence,
    unreadCount: shouldIncrementUnread ? current.unreadCount + 1 : current.unreadCount,
    lastMessage: updatesPreview ? delta.content : current.lastMessage,
    lastMessageType: updatesPreview ? delta.contentType : current.lastMessageType,
    lastMessageAt: updatesPreview ? delta.createdAt : current.lastMessageAt,
  }
  return {
    ...source,
    [delta.conversationId]: next,
  }
}

export function applyConversationRead(
  source: ConversationSummaryMap,
  delta: ConversationReadDelta,
): ConversationSummaryMap {
  if (!delta.conversationId || delta.readerId !== delta.currentUserId) return source
  const current = source[delta.conversationId]
    || emptyConversationSummary(delta.conversationId, delta.currentUserId)
  if (!current
    || delta.lastReadSequence < current.lastReadSequence
    || conversationReadRequiresSnapshot(source, delta)) return source

  return {
    ...source,
    [delta.conversationId]: {
      ...current,
      lastSequence: Math.max(current.lastSequence, safeCount(delta.lastSequence)),
      lastReadSequence: Math.max(current.lastReadSequence, safeCount(delta.lastReadSequence)),
      unreadCount: safeCount(delta.unreadCount),
    },
  }
}

/**
 * A read event contains an exact unread count only for the server sequence at
 * which it was committed. If a newer message is already present locally, that
 * count cannot be merged safely because some newer messages may be outgoing.
 */
export function conversationReadRequiresSnapshot(
  source: ConversationSummaryMap,
  delta: ConversationReadDelta,
): boolean {
  if (!delta.conversationId || delta.readerId !== delta.currentUserId) return false
  const current = source[delta.conversationId]
  return Boolean(current && delta.lastSequence < current.lastSequence)
}

export function removeConversationSummary(
  source: ConversationSummaryMap,
  conversationId: string,
): ConversationSummaryMap {
  if (!conversationId || !source[conversationId]) return source
  const next = { ...source }
  delete next[conversationId]
  return next
}

function normalizeConversationSummary(raw: ConversationSummary): ConversationSummary | null {
  if (!raw || !raw.conversationId) return null
  const identity = conversationIdentity(raw.conversationId, raw.targetId)
  if (!identity) return null
  return {
    conversationId: raw.conversationId,
    kind: identity.kind,
    targetId: identity.targetId,
    lastSequence: safeCount(raw.lastSequence),
    lastReadSequence: safeCount(raw.lastReadSequence),
    unreadCount: safeCount(raw.unreadCount),
    lastMessage: raw.lastMessage,
    lastMessageType: raw.lastMessageType,
    lastMessageAt: raw.lastMessageAt,
    pinned: raw.pinned === true,
    muted: raw.muted === true,
  }
}

function emptyConversationSummary(
  conversationId: string,
  currentUserId: number,
): ConversationSummary | null {
  const identity = conversationIdentity(conversationId, undefined, currentUserId)
  if (!identity) return null
  return {
    conversationId,
    ...identity,
    lastSequence: 0,
    lastReadSequence: 0,
    unreadCount: 0,
    pinned: false,
    muted: false,
  }
}

function conversationIdentity(
  conversationId: string,
  suppliedTargetId?: number,
  currentUserId?: number,
): { kind: ConversationKind; targetId: number } | null {
  const parts = conversationId.split(':')
  if (parts[0] === 'private' && parts.length === 3) {
    const first = Number(parts[1])
    const second = Number(parts[2])
    if (!positiveSafeInteger(first) || !positiveSafeInteger(second)) return null
    const targetId = positiveSafeInteger(suppliedTargetId)
      ? suppliedTargetId
      : currentUserId === first
        ? second
        : first
    return positiveSafeInteger(targetId) ? { kind: 'private', targetId } : null
  }
  if ((parts[0] === 'group' || parts[0] === 'temporary') && parts.length === 2) {
    const parsedTargetId = Number(parts[1])
    const targetId = positiveSafeInteger(suppliedTargetId) ? suppliedTargetId : parsedTargetId
    if (!positiveSafeInteger(targetId)) return null
    return {
      kind: parts[0],
      targetId,
    }
  }
  return null
}

function safeCount(value: number | undefined): number {
  return Number.isSafeInteger(value) && (value || 0) >= 0 ? value! : 0
}

function positiveSafeInteger(value: number | undefined): value is number {
  return Number.isSafeInteger(value) && (value || 0) > 0
}
