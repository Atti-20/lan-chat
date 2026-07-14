import { computed, readonly, ref, shallowRef } from 'vue'
import { api } from '../services/api'
import type {
  ChatGroup,
  ChatMessage,
  Conversation,
  FileUpload,
  Friend,
  FriendRequest,
  GroupMember,
  User,
  WsEnvelope,
} from '../types'
import { conversationPreview } from '../utils/format'
import { createClientMessageId } from '../utils/id'
import { useAuth } from './useAuth'
import { useToast } from './useToast'
import { useWebSocket } from './useWebSocket'

export type ChatSection = 'messages' | 'contacts' | 'groups'

export function useChat() {
  const { currentUser } = useAuth()
  const toast = useToast()
  const friends = ref<Friend[]>([])
  const groups = ref<ChatGroup[]>([])
  const requests = ref<FriendRequest[]>([])
  const members = ref<GroupMember[]>([])
  const messages = ref<ChatMessage[]>([])
  const selected = shallowRef<Conversation | null>(null)
  const section = ref<ChatSection>('messages')
  const query = ref('')
  const loading = ref(true)
  const loadingMessages = ref(false)
  const typingLabel = ref('')
  const onlineIds = ref<Set<number>>(new Set())

  const conversations = computed<Conversation[]>(() => {
    const privateChats = friends.value.map((friend): Conversation => ({
      id: friend.friendId,
      kind: 'private',
      name: friend.remark || friend.nickname,
      avatar: friend.avatar,
      subtitle: friend.signature,
      lastMessage: conversationPreview(friend.lastMessageType, friend.lastMessage),
      lastMessageType: friend.lastMessageType,
      lastMessageTime: friend.lastMessageTime,
      online: onlineIds.value.has(friend.friendId) || friend.online === 1,
      pinned: friend.isPinned === 1,
      muted: friend.isMuted === 1,
      source: friend,
    }))
    const groupChats = groups.value.map((group): Conversation => ({
      id: group.id,
      kind: 'group',
      name: group.groupName,
      avatar: group.avatar,
      subtitle: group.announcement,
      lastMessage: conversationPreview(group.lastMessageType, group.lastMessage),
      lastMessageType: group.lastMessageType,
      lastMessageTime: group.lastMessageTime,
      source: group,
    }))
    return [...privateChats, ...groupChats].sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
      return new Date(b.lastMessageTime || 0).getTime() - new Date(a.lastMessageTime || 0).getTime()
    })
  })

  const visibleConversations = computed(() => {
    const base = section.value === 'contacts'
      ? conversations.value.filter((item) => item.kind === 'private')
      : section.value === 'groups'
        ? conversations.value.filter((item) => item.kind === 'group')
        : conversations.value
    const needle = query.value.trim().toLocaleLowerCase('zh-CN')
    return needle ? base.filter((item) => item.name.toLocaleLowerCase('zh-CN').includes(needle)) : base
  })

  const ws = useWebSocket({
    onMessage: handleSocketMessage,
    onError: (message) => toast.push(message, 'warning'),
  })

  async function load(): Promise<void> {
    loading.value = true
    try {
      const [friendList, groupList, requestList] = await Promise.all([
        api.friends.list(),
        api.groups.list(),
        api.friends.requests(),
      ])
      friends.value = friendList || []
      groups.value = groupList || []
      requests.value = await enrichRequests(requestList || [])
      ws.connect()
      if (!selected.value && conversations.value.length > 0 && window.innerWidth >= 760) {
        await selectConversation(conversations.value[0])
      }
    } finally {
      loading.value = false
    }
  }

  async function enrichRequests(items: FriendRequest[]): Promise<FriendRequest[]> {
    return Promise.all(items.map(async (request) => {
      try {
        const sender = await api.user.byId(request.fromUserId)
        return { ...request, sender }
      } catch {
        return request
      }
    }))
  }

  async function refreshLists(): Promise<void> {
    const [friendList, groupList, requestList] = await Promise.all([
      api.friends.list(),
      api.groups.list(),
      api.friends.requests(),
    ])
    friends.value = friendList || []
    groups.value = groupList || []
    requests.value = await enrichRequests(requestList || [])
  }

  async function selectConversation(conversation: Conversation): Promise<void> {
    selected.value = conversation
    loadingMessages.value = true
    typingLabel.value = ''
    try {
      const history = conversation.kind === 'group'
        ? await api.chat.groupHistory(conversation.id)
        : await api.chat.privateHistory(conversation.id)
      messages.value = (history || []).map(normalizeMessage)
      members.value = conversation.kind === 'group' ? await api.groups.members(conversation.id) : []
      if (conversation.kind === 'private') await api.chat.markRead(conversation.id)
    } finally {
      loadingMessages.value = false
    }
  }

function normalizeMessage(message: ChatMessage): ChatMessage {
const resolvedType = (message.type && message.type !== 'chat') ? message.type : (message.contentType || 'text')
return {
...message,
type: resolvedType,
      createTime: message.createTime || message.timestamp,
      isBurn: message.isBurn === true ? 1 : Number(message.isBurn || 0),
    }
  }

  function belongsToSelected(message: ChatMessage): boolean {
    const conversation = selected.value
    const me = currentUser.value?.id
    if (!conversation || !me) return false
    if (conversation.kind === 'group') return message.groupId === conversation.id
    return !message.groupId && (
      (message.fromUserId === conversation.id && message.toUserId === me)
      || (message.fromUserId === me && message.toUserId === conversation.id)
    )
  }

  function handleSocketMessage(envelope: WsEnvelope): void {
    if (envelope.type === 'error') {
      toast.push(envelope.content || '实时操作失败', 'danger')
      return
    }
    if (envelope.type === 'online-list') {
      try {
        const users = JSON.parse(envelope.content || '[]') as User[]
        onlineIds.value = new Set(users.map((user) => user.id))
      } catch {
        onlineIds.value = new Set()
      }
      return
    }
    if (envelope.type === 'friend') {
      toast.push(envelope.content || '好友状态有更新', 'success')
      void refreshLists()
      return
    }
    if (envelope.type === 'typing') {
      if (belongsToSelected(envelope as ChatMessage) && envelope.fromUserId !== currentUser.value?.id) {
        typingLabel.value = `${envelope.fromNickname || '对方'}正在输入…`
        window.setTimeout(() => { typingLabel.value = '' }, 2600)
      }
      return
    }
    if (envelope.type === 'recall' || envelope.type === 'burn') {
      const target = messages.value.find((item) => item.messageId === envelope.messageId)
      if (target) {
        if (envelope.type === 'recall') target.isRecalled = 1
        if (envelope.type === 'burn') {
          target.status = 2
          target.content = ''
        }
      }
      return
    }
    if (envelope.type !== 'chat') return

    const message = normalizeMessage(envelope as ChatMessage)
    if (belongsToSelected(message) && !messages.value.some((item) => item.messageId === message.messageId)) {
      messages.value.push(message)
      if (selected.value?.kind === 'private' && message.fromUserId !== currentUser.value?.id) {
        void api.chat.markRead(message.fromUserId)
      }
    }
    updateConversationPreview(message)
  }

  function updateConversationPreview(message: ChatMessage): void {
    if (message.groupId) {
      const group = groups.value.find((item) => item.id === message.groupId)
      if (group) {
        group.lastMessage = message.content
        group.lastMessageType = message.type
        group.lastMessageTime = message.createTime
      }
      return
    }
    const me = currentUser.value?.id
    const otherId = message.fromUserId === me ? message.toUserId : message.fromUserId
    const friend = friends.value.find((item) => item.friendId === otherId)
    if (friend) {
      friend.lastMessage = message.content
      friend.lastMessageType = message.type
      friend.lastMessageTime = message.createTime
    }
  }

  function sendText(content: string, options: { burn?: boolean; replyToId?: string } = {}): boolean {
    const conversation = selected.value
    if (!conversation || !content.trim()) return false
    const payload: Record<string, unknown> = {
      type: 'chat',
      messageId: createClientMessageId(),
      contentType: 'text',
      content: content.trim(),
      isBurn: Boolean(options.burn),
      burnDuration: 5,
      replyToId: options.replyToId || null,
    }
    payload[conversation.kind === 'group' ? 'groupId' : 'toUserId'] = conversation.id
    const sent = ws.send(payload)
    if (!sent) toast.push('实时连接尚未恢复，消息未发送', 'warning')
    return sent
  }

  async function sendFile(file: File): Promise<void> {
    const conversation = selected.value
    if (!conversation) return
    const uploaded = await api.files.upload(file)
    const image = file.type.startsWith('image/')
    const content = image
      ? JSON.stringify({ url: uploaded.url, thumbnailUrl: uploaded.thumbnailUrl, originalUrl: uploaded.url })
      : JSON.stringify({ name: uploaded.originalName || file.name, size: uploaded.fileSize, url: uploaded.url })
    const payload: Record<string, unknown> = {
      type: 'chat',
      messageId: createClientMessageId(),
      contentType: image ? 'image' : 'file',
      content,
      isBurn: false,
    }
    payload[conversation.kind === 'group' ? 'groupId' : 'toUserId'] = conversation.id
    if (!ws.send(payload)) throw new Error('实时连接尚未恢复，文件未发送')
  }

  function sendTyping(): void {
    const conversation = selected.value
    if (!conversation) return
    ws.send({
      type: 'typing',
      toUserId: conversation.kind === 'private' ? conversation.id : null,
      groupId: conversation.kind === 'group' ? conversation.id : null,
    })
  }

  function recall(messageId: string): void {
    if (!ws.send({ type: 'recall', messageId })) toast.push('连接已断开，暂时无法撤回', 'warning')
  }

  function burn(messageId: string): void {
    if (!ws.send({ type: 'burn', messageId })) toast.push('连接已断开，暂时无法焚毁', 'warning')
  }

  async function handleRequest(requestId: number, accept: boolean): Promise<void> {
    await api.friends.handleRequest(requestId, accept)
    toast.push(accept ? '已添加为好友' : '已忽略申请', accept ? 'success' : 'default')
    await refreshLists()
  }

  async function createGroup(name: string, memberIds: number[]): Promise<ChatGroup> {
    const group = await api.groups.create(name, memberIds)
    toast.push('群聊已创建', 'success')
    await refreshLists()
    return group
  }

  async function sendFriendRequest(userId: number, message: string): Promise<void> {
    await api.friends.sendRequest(userId, message)
    toast.push('好友申请已发送', 'success')
  }

  async function togglePin(): Promise<void> {
    if (selected.value?.kind !== 'private') return
    await api.friends.togglePin(selected.value.id)
    await refreshLists()
  }

  async function toggleMute(): Promise<void> {
    if (selected.value?.kind !== 'private') return
    await api.friends.toggleMute(selected.value.id)
    await refreshLists()
  }

  async function deleteFriend(): Promise<void> {
    if (selected.value?.kind !== 'private') return
    await api.friends.delete(selected.value.id)
    selected.value = null
    messages.value = []
    await refreshLists()
  }

  async function updateRemark(remark: string): Promise<void> {
    if (selected.value?.kind !== 'private') return
    await api.friends.setRemark(selected.value.id, remark)
    await refreshLists()
    // 刷新后更新 selected 的名称
    const updated = conversations.value.find(
      (c) => c.kind === 'private' && c.id === selected.value?.id,
    )
    if (updated) selected.value = updated
  }

  return {
    friends: readonly(friends),
    groups: readonly(groups),
    requests: readonly(requests),
    members: readonly(members),
    messages: readonly(messages),
    selected,
    section,
    query,
    loading: readonly(loading),
    loadingMessages: readonly(loadingMessages),
    typingLabel: readonly(typingLabel),
    conversations,
    visibleConversations,
    connected: ws.connected,
    reconnecting: ws.reconnecting,
    load,
    refreshLists,
    selectConversation,
    sendText,
    sendFile,
    sendTyping,
    recall,
    burn,
    handleRequest,
    createGroup,
    sendFriendRequest,
    togglePin,
    toggleMute,
    deleteFriend,
    updateRemark,
    disconnect: ws.disconnect,
  }
}
