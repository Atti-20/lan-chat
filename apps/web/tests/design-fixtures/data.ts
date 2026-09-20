import type { ChatMessage, Conversation, User } from '../../src/types'

// Presentation fixtures only. These are not backend responses or new business states.
export const user: User = { id: 1, username: 'design-fixture', nickname: '我', avatar: 'letter:我:#006fe8' }
export const conversations: Conversation[] = [
  {
    id: 2, conversationId: 'private:1:2', kind: 'private',
    name: '陈小溪 · 中英文长昵称 MeshX Design Foundation',
    avatar: 'letter:陈:#007aff', online: true, unreadCount: 128, pinned: true,
    lastMessage: '这是一段用于验证长文本省略的会话预览，不应挤出未读标记。', lastMessageType: 'text',
    lastMessageTime: '2026-09-09T00:12:00+08:00',
    source: { id: 2, friendId: 2, username: 'chen', nickname: '陈小溪' },
  },
  {
    id: 3, conversationId: 'private:1:3', kind: 'private', name: '项目协作 / Project',
    avatar: 'letter:P:#5856d6', muted: true, pendingCount: 2, lastMessage: '等待网络恢复',
    lastMessageType: 'text', lastMessageTime: '2026-09-09T00:10:00+08:00',
    source: { id: 3, friendId: 3, username: 'project', nickname: '项目协作' },
  },
]

export const messages: ChatMessage[] = [
  { messageId: 'peer', fromUserId: 2, content: '中文 English 混排。长链接应在气泡内换行：\nhttps://example.test/' + 'long-path-'.repeat(13), deliveryState: 'SENT' },
  { messageId: 'sent', fromUserId: 1, content: '服务端已确认的消息。', deliveryState: 'SENT' },
  { messageId: 'sending', fromUserId: 1, clientMsgId: 'design-sending', content: '正在发送，请保留同一条消息。', deliveryState: 'SENDING' },
  { messageId: 'waiting', fromUserId: 1, clientMsgId: 'design-waiting', content: '离线等待连接', deliveryState: 'WAITING_NETWORK' },
  { messageId: 'failed', fromUserId: 1, clientMsgId: 'design-failed', content: '失败后由原消息发起重试', deliveryState: 'FAILED', errorMessage: '未收到确认' },
  { messageId: 'recalled', fromUserId: 2, content: '撤回后不得显示这段内容', deliveryState: 'SENT', isRecalled: 1 },
  { messageId: 'burned', fromUserId: 2, content: '焚毁后不得显示这段内容', deliveryState: 'SENT', status: 2 },
].map(message => ({ ...message, type: 'text', conversationId: 'private:1:2', createTime: '2026-09-09T00:12:00+08:00' })) as ChatMessage[]
