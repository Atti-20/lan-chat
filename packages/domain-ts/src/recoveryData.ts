import type { ChatMessage, OutboxEntry } from './models'
import type { RecoveryPlan } from './recovery'

/** Effects apply to existing application models before the atomic snapshot commit. */
export function applyRecoveryData(plan: RecoveryPlan, messages: readonly ChatMessage[], outbox: readonly OutboxEntry[]): {
  messages: ChatMessage[]; outbox: OutboxEntry[]; clearPreviews: Set<string>
} {
  const clearPreviews = new Set(plan.effects.revokeConversations)
  const revoked = plan.effects.revokeConversations
  const terminalClients = new Set<string>()
  const nextMessages = messages.map(message => {
    if (!revoked.has(message.conversationId || '') && !plan.effects.eraseMessages.has(message.messageId)) return message
    if (message.conversationId) clearPreviews.add(message.conversationId)
    if (message.clientMsgId) terminalClients.add(`${message.conversationId}\n${message.fromUserId}\n${message.clientMsgId}`)
    // Whitelist fields: content, reply metadata, attachments and arbitrary preview fields are removed.
    return { messageId: message.messageId, clientMsgId: message.clientMsgId, conversationId: message.conversationId,
      fromUserId: message.fromUserId, sequence: message.sequence, createTime: message.createTime,
      type: 'text', content: '', deliveryState: message.deliveryState,
      isRecalled: plan.next.messages.get(message.messageId)?.state === 'RECALLED' ? 1 : 0 } satisfies ChatMessage
  })
  const nextOutbox = outbox.map(entry => {
    const erase = revoked.has(entry.conversationId) || terminalClients.has(`${entry.conversationId}\n${plan.next.context.userId}\n${entry.clientMsgId}`)
      || entry.recoveryDisposition === 'DROP_BODY_REVOKED'
    const hold = erase || plan.effects.stopAutomaticSend.has(entry.conversationId) || Boolean(entry.recoveryDisposition)
    if (!hold) return entry
    return { ...entry, state: 'FAILED' as const, recoveryDisposition: erase ? 'DROP_BODY_REVOKED' as const : 'NEEDS_USER_ACTION' as const,
      lastError: erase ? 'DROP_BODY_REVOKED' : 'NEEDS_USER_ACTION',
      payload: erase ? { contentType: 'text', content: '', isBurn: false } : entry.payload }
  })
  return { messages: nextMessages, outbox: nextOutbox, clearPreviews }
}
