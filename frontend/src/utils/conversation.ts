import type { Conversation } from '../types'

export function privateConversationId(firstUserId: number, secondUserId: number): string {
  if (firstUserId <= 0 || secondUserId <= 0 || firstUserId === secondUserId) {
    throw new Error('无法生成私聊会话标识')
  }
  const lower = Math.min(firstUserId, secondUserId)
  const upper = Math.max(firstUserId, secondUserId)
  return `private:${lower}:${upper}`
}

export function groupConversationId(groupId: number): string {
  if (groupId <= 0) throw new Error('无法生成群聊会话标识')
  return `group:${groupId}`
}

export function temporaryConversationId(roomId: number): string {
  if (roomId <= 0) throw new Error('无法生成临时房间会话标识')
  return `temporary:${roomId}`
}

export function resolveConversationId(
  conversation: Pick<Conversation, 'kind' | 'id'>,
  currentUserId: number,
): string {
  if (conversation.kind === 'group') return groupConversationId(conversation.id)
  if (conversation.kind === 'temporary') return temporaryConversationId(conversation.id)
  return privateConversationId(currentUserId, conversation.id)
}
