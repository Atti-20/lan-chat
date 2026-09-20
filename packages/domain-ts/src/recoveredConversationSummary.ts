/** Summaries use only the current verified image, never legacy server previews. */
export interface RecoveredSummaryRow {
  conversationId: string
  messageId: string
  sequence: number
  state: 'NORMAL' | 'RECALLED' | 'BURNED' | 'UNAVAILABLE'
  fromUserId?: number
  content?: string
  contentType?: string
  isBurn?: boolean
  timestamp?: string
}
export interface RecoveredConversationSummary {
  preview: string
  unreadCount: number
  lastSequence: number
  timestamp?: string
}
function preview(row: RecoveredSummaryRow): string {
  if (row.state === 'RECALLED') return '这条消息已撤回'
  if (row.state === 'BURNED') return '这条消息已焚毁'
  if (row.state === 'UNAVAILABLE') return '这条消息已不可用'
  if (row.isBurn) return '阅后即焚消息'
  const labels: Record<string, string> = {image:'图片',file:'文件',voice:'语音',video:'视频',broadcast:'广播通知'}
  if (row.contentType === 'text') return row.content || '还没有消息'
  return labels[row.contentType || ''] || '暂不预览此类消息'
}
export function recoveredConversationSummaries(rows: Iterable<RecoveredSummaryRow>, userId: number,
  readPositions: ReadonlyMap<string, number>): Map<string, RecoveredConversationSummary> {
  const result = new Map<string, RecoveredConversationSummary>()
  for (const row of rows) {
    if (!Number.isSafeInteger(row.sequence) || row.sequence <= 0) continue
    const previous = result.get(row.conversationId) ?? {preview:'',unreadCount:0,lastSequence:0}
    const read = readPositions.get(row.conversationId)
    if (read !== undefined && row.state === 'NORMAL' && row.fromUserId !== userId && row.sequence > read) previous.unreadCount++
    if (row.sequence >= previous.lastSequence) {
      previous.preview = preview(row)
      previous.lastSequence = row.sequence
      previous.timestamp = row.state === 'NORMAL' ? row.timestamp : undefined
    }
    result.set(row.conversationId, previous)
  }
  return result
}
