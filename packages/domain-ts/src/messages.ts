import type { ChatMessage } from './models'

export function normalizeMessage(message: ChatMessage, currentUserId: number | undefined): ChatMessage {
  const resolvedType = message.type && message.type !== 'chat'
    ? message.type
    : (message.contentType || 'text')
  const own = message.fromUserId === currentUserId
  return {
    ...message,
    type: resolvedType,
    contentType: resolvedType,
    createTime: message.createTime || message.timestamp || message.clientCreatedAt,
    isBurn: message.isBurn === true ? 1 : Number(message.isBurn || 0),
    isRecalled: Number(message.isRecalled || 0),
    deliveryState: message.deliveryState || (own
      ? (message.status === 1 ? 'READ' : 'SENT')
      : 'DELIVERED'),
  }
}

export function mergeMessages(
  current: readonly ChatMessage[],
  incoming: readonly ChatMessage[],
  currentUserId: number | undefined,
): ChatMessage[] {
  const merged: ChatMessage[] = []
  ;[...current, ...incoming].forEach((raw) => {
    const next = normalizeMessage(raw, currentUserId)
    const index = merged.findIndex((item) =>
      (next.messageId && item.messageId === next.messageId)
      || (next.clientMsgId && item.clientMsgId === next.clientMsgId))
    if (index < 0) merged.push(next)
    else merged[index] = normalizeMessage({ ...merged[index], ...next }, currentUserId)
  })
  return sortMessages(merged)
}

export function sortMessages(source: readonly ChatMessage[]): ChatMessage[] {
  return [...source].sort((first, second) => {
    if (first.sequence != null && second.sequence != null) return first.sequence - second.sequence
    if (first.sequence != null) return -1
    if (second.sequence != null) return 1
    return new Date(first.clientCreatedAt || first.createTime || 0).getTime()
      - new Date(second.clientCreatedAt || second.createTime || 0).getTime()
  })
}
