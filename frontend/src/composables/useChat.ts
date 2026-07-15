import { computed, onBeforeUnmount, readonly, ref, shallowRef, watch } from 'vue'
import { api } from '../services/api'
import {
  cacheMessages,
  clearLocalChatDatabase,
  deleteCachedMessagesByClientMsgId,
  loadConversationDirectory,
  loadCachedMessages,
  loadPositions,
  saveConversationDirectory,
  savePosition,
} from '../services/localChatDb'
import type {
  ChatGroup,
  ChatMessage,
  ChatSendPayload,
  Conversation,
  FileUpload,
  Friend,
  FriendRequest,
  GroupMember,
  MessageDeliveryState,
  OutboxEntry,
  User,
  WsEnvelope,
} from '../types'
import { resolveConversationId, groupConversationId, privateConversationId } from '../utils/conversation'
import { conversationPreview } from '../utils/format'
import { createClientMessageId } from '../utils/id'
import { clearCacheOwner, clearSession } from '../utils/storage'
import { useAuth } from './useAuth'
import { useOutbox } from './useOutbox'
import { useToast } from './useToast'
import { useWebSocket } from './useWebSocket'

export type ChatSection = 'messages' | 'contacts' | 'groups' | 'admin'

type AckOutcome = 'ACK' | 'ERROR'

interface AckWaiter {
  resolve: (outcome: AckOutcome | 'TIMEOUT') => void
  timer: number
}

export function useChat() {
  const { currentUser } = useAuth()
  const toast = useToast()
  const outbox = useOutbox()
  const friends = ref<Friend[]>([])
  const groups = ref<ChatGroup[]>([])
  const requests = ref<FriendRequest[]>([])
  const members = ref<GroupMember[]>([])
  const messages = ref<ChatMessage[]>([])
  const messageSearchResults = ref<ChatMessage[]>([])
  const messageSearchLoading = shallowRef(false)
  const messageSearchError = shallowRef('')
  const selected = shallowRef<Conversation | null>(null)
  const section = shallowRef<ChatSection>('messages')
  const query = shallowRef('')
  const loading = shallowRef(true)
  const loadingMessages = shallowRef(false)
  const typingLabel = shallowRef('')
  const onlineIds = ref<Set<number>>(new Set())

  const ackWaiters = new Map<string, AckWaiter>()
  const burnTimers = new Map<string, number>()
  const burnConversationIds = new Map<string, string>()
  const runtimePositions = new Map<string, number>()
  let flushPromise: Promise<void> | null = null
  let warnedAboutVolatileOutbox = false
  let syncResolver: ((hasMore: boolean) => void) | null = null
  let syncRejecter: ((cause: Error) => void) | null = null
  let syncRequestId: string | null = null
  let syncTimer: number | null = null
  let messageSearchTimer: number | null = null
  let messageSearchSequence = 0

  onBeforeUnmount(() => {
    burnTimers.forEach((timer) => window.clearTimeout(timer))
    burnTimers.clear()
    burnConversationIds.clear()
    if (messageSearchTimer !== null) window.clearTimeout(messageSearchTimer)
  })

  watch(
    () => [section.value, query.value] as const,
    ([currentSection, rawQuery], _previous, onCleanup) => {
      const sequence = ++messageSearchSequence
      if (messageSearchTimer !== null) {
        window.clearTimeout(messageSearchTimer)
        messageSearchTimer = null
      }
      messageSearchResults.value = []
      messageSearchLoading.value = false
      messageSearchError.value = ''

      const keyword = rawQuery.trim()
      if (currentSection !== 'messages' || keyword.length < 2) return

      const timer = window.setTimeout(async () => {
        messageSearchLoading.value = true
        try {
          const found = await api.chat.search(keyword, 50)
          if (sequence === messageSearchSequence) messageSearchResults.value = found || []
        } catch (cause) {
          if (sequence === messageSearchSequence) {
            messageSearchError.value = cause instanceof Error ? cause.message : '搜索消息失败，请稍后重试'
          }
        } finally {
          if (sequence === messageSearchSequence) messageSearchLoading.value = false
          if (messageSearchTimer === timer) messageSearchTimer = null
        }
      }, 260)
      messageSearchTimer = timer
      onCleanup(() => window.clearTimeout(timer))
    },
  )

  const conversations = computed<Conversation[]>(() => {
    const me = currentUser.value?.id
    const privateChats = friends.value.map((friend): Conversation => {
      const conversationId = me ? privateConversationId(me, friend.friendId) : ''
      return {
        id: friend.friendId,
        conversationId,
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
        pendingCount: outbox.entries.value.filter((entry) => entry.conversationId === conversationId).length,
        source: friend,
      }
    })
    const groupChats = groups.value.map((group): Conversation => {
      const conversationId = groupConversationId(group.id)
      return {
        id: group.id,
        conversationId,
        kind: 'group',
        name: group.groupName,
        avatar: group.avatar,
        subtitle: group.announcement,
        lastMessage: conversationPreview(group.lastMessageType, group.lastMessage),
        lastMessageType: group.lastMessageType,
        lastMessageTime: group.lastMessageTime,
        pendingCount: outbox.entries.value.filter((entry) => entry.conversationId === conversationId).length,
        source: group,
      }
    })
    return [...privateChats, ...groupChats].sort((first, second) => {
      if (first.pinned !== second.pinned) return first.pinned ? -1 : 1
      return new Date(second.lastMessageTime || 0).getTime()
        - new Date(first.lastMessageTime || 0).getTime()
    })
  })

  const visibleConversations = computed(() => {
    const base = section.value === 'contacts'
      ? conversations.value.filter((item) => item.kind === 'private')
      : section.value === 'groups'
        ? conversations.value.filter((item) => item.kind === 'group')
        : section.value === 'admin'
          ? []
          : conversations.value
    const needle = query.value.trim().toLocaleLowerCase('zh-CN')
    return needle
      ? base.filter((item) => item.name.toLocaleLowerCase('zh-CN').includes(needle))
      : base
  })

  const ws = useWebSocket({
    onMessage: handleSocketMessage,
    onReady: synchronizeAfterReconnect,
    onError: (message) => toast.push(message, 'warning'),
    refreshAuth: api.auth.refreshSession,
    onAuthFailed: (reason) => {
      clearSession()
      if (reason === 'FORCE_LOGOUT') {
        void clearLocalChatDatabase()
          .then(() => clearCacheOwner())
          .catch(() => undefined)
          .finally(() => window.location.replace('/'))
        return
      }
      // Refresh 过期不应销毁离线发件箱；同一用户重新登录后继续补发，
      // 若改用其他账号，prepareLocalCache 会按 owner 清理隔离数据。
      window.location.replace('/')
    },
  })

  async function load(): Promise<void> {
    loading.value = true
    try {
      try {
        await outbox.hydrate()
      } catch {
        toast.push('本地离线队列暂不可用，本次会话仍可在线使用', 'warning')
      }
      const cachedDirectory = await loadConversationDirectory().catch(() => null)
      if (cachedDirectory) {
        friends.value = cachedDirectory.friends || []
        groups.value = cachedDirectory.groups || []
      }
      try {
        const [friendList, groupList, requestList] = await Promise.all([
          api.friends.list(),
          api.groups.list(),
          api.friends.requests(),
        ])
        friends.value = friendList || []
        groups.value = groupList || []
        requests.value = await enrichRequests(requestList || [])
        await saveConversationDirectory(friends.value, groups.value).catch(() => undefined)
      } catch (cause) {
        if (!cachedDirectory) throw cause
        requests.value = []
        toast.push('节点暂不可达，已载入本机会话目录', 'warning', 2600)
      }
      restoreOptimisticMessages()
      ws.connect()
    } finally {
      loading.value = false
    }
  }

  function restoreOptimisticMessages(): void {
    const user = currentUser.value
    if (!user) return
    const currentConversationId = selected.value?.conversationId
    if (!currentConversationId) return
    const pending = outbox.entries.value
      .filter((entry) => entry.conversationId === currentConversationId)
      .map((entry) => optimisticMessage(entry, user))
    mergeCurrentMessages(pending)
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
    await saveConversationDirectory(friends.value, groups.value).catch(() => undefined)
  }

  async function selectConversation(conversation: Conversation): Promise<void> {
    const user = currentUser.value
    if (!user) throw new Error('登录状态已失效')
    const conversationId = conversation.conversationId || resolveConversationId(conversation, user.id)
    selected.value = { ...conversation, conversationId }
    loadingMessages.value = true
    typingLabel.value = ''

    const cached = await loadCachedMessages(conversationId)
      .then((items) => items.map(normalizeMessage))
      .catch(() => [] as ChatMessage[])
    const optimistic = outbox.entries.value
      .filter((entry) => entry.conversationId === conversationId)
      .map((entry) => optimisticMessage(entry, user))
    messages.value = mergeMessages(cached, optimistic)
    scheduleBurnCountdowns(messages.value)
    await recordReceivedPositions(cached)

    try {
      const history = await api.chat.history(conversationId)
      const normalized = (history || []).map(normalizeMessage)
      messages.value = mergeMessages(normalized, optimistic)
      scheduleBurnCountdowns(messages.value)
      await cacheMessages(messages.value).catch(() => undefined)
      await recordReceivedPositions(normalized)
      members.value = conversation.kind === 'group' ? await api.groups.members(conversation.id) : []
      sendReadPosition(conversationId, normalized)
    } catch {
      members.value = conversation.kind === 'group'
        ? await api.groups.members(conversation.id).catch(() => [])
        : []
      toast.push('当前使用本地缓存，连接恢复后会自动同步', 'warning', 2600)
    } finally {
      loadingMessages.value = false
    }
  }

  function normalizeMessage(message: ChatMessage): ChatMessage {
    const resolvedType = message.type && message.type !== 'chat'
      ? message.type
      : (message.contentType || 'text')
    const own = message.fromUserId === currentUser.value?.id
    return {
      ...message,
      type: resolvedType,
      contentType: resolvedType,
      createTime: message.createTime || message.timestamp || message.clientCreatedAt,
      isBurn: message.isBurn === true ? 1 : Number(message.isBurn || 0),
      deliveryState: message.deliveryState || (own
        ? (message.status === 1 ? 'READ' : 'SENT')
        : 'DELIVERED'),
    }
  }

  function belongsToSelected(message: ChatMessage): boolean {
    return Boolean(selected.value?.conversationId
      && message.conversationId === selected.value.conversationId)
  }

  async function handleSocketMessage(envelope: WsEnvelope): Promise<void> {
    switch (envelope.event) {
      case 'ERROR':
        handleProtocolError(envelope)
        return
      case 'ONLINE_LIST':
        handleOnlineList(envelope)
        return
      case 'PRESENCE_CHANGED':
        handlePresence(envelope)
        return
      case 'FRIEND_CHANGED':
        toast.push(String(envelope.payload.message || '好友状态有更新'), 'success')
        await refreshLists()
        return
      case 'TYPING_START':
        handleTypingEvent(envelope)
        return
      case 'TYPING_STOP':
        typingLabel.value = ''
        return
      case 'CHAT_ACK':
        await handleAck(envelope)
        return
      case 'CHAT_DELIVER':
        await handleDelivery(envelope)
        return
      case 'SYNC_RESPONSE':
        await handleSyncResponse(envelope)
        return
      case 'CHAT_RECALL':
      case 'CHAT_BURN':
        await handleMessageMutation(envelope)
        return
      case 'CHAT_READ':
        handleReadEvent(envelope)
        return
      default:
        // V1 客户端必须忽略未知但非致命事件，以便服务端向前兼容。
    }
  }

  function handleProtocolError(envelope: WsEnvelope): void {
    const message = String(envelope.payload.message || '实时操作失败')
    const clientMsgId = envelope.clientMsgId
    if (clientMsgId) {
      settleAck(clientMsgId, 'ERROR')
      void outbox.update(clientMsgId, { state: 'FAILED', lastError: message })
      updateDeliveryState(clientMsgId, 'FAILED', message)
    }
    toast.push(message, 'danger')
  }

  function handleOnlineList(envelope: WsEnvelope): void {
    const users = Array.isArray(envelope.payload.users) ? envelope.payload.users as User[] : []
    onlineIds.value = new Set(users.map((user) => user.id))
  }

  function handlePresence(envelope: WsEnvelope): void {
    const userId = Number(envelope.payload.userId)
    const online = envelope.payload.status === 'online'
    const next = new Set(onlineIds.value)
    if (online) next.add(userId)
    else next.delete(userId)
    onlineIds.value = next
  }

  function handleTypingEvent(envelope: WsEnvelope): void {
    if (envelope.conversationId !== selected.value?.conversationId) return
    if (Number(envelope.payload.userId) === currentUser.value?.id) return
    typingLabel.value = `${String(envelope.payload.nickname || '对方')}正在输入…`
    window.setTimeout(() => { typingLabel.value = '' }, 2_600)
  }

  async function handleAck(envelope: WsEnvelope): Promise<void> {
    const clientMsgId = envelope.clientMsgId || String(envelope.payload.clientMsgId || '')
    if (!clientMsgId) return
    const messageId = String(envelope.payload.messageId || '')
    const conversationId = envelope.conversationId || String(envelope.payload.conversationId || '')
    const sequence = Number(envelope.payload.sequence)
    const serverTime = Number(envelope.payload.serverTime)

    messages.value = messages.value.map((message) => message.clientMsgId === clientMsgId
      ? normalizeMessage({
          ...message,
          messageId: messageId || message.messageId,
          conversationId: conversationId || message.conversationId,
          sequence: Number.isFinite(sequence) ? sequence : message.sequence,
          createTime: Number.isFinite(serverTime) ? new Date(serverTime).toISOString() : message.createTime,
          deliveryState: 'SENT',
          errorMessage: undefined,
        })
      : message)
    messages.value = sortMessages(messages.value)
    settleAck(clientMsgId, 'ACK')
    await deleteCachedMessagesByClientMsgId(clientMsgId).catch(() => undefined)
    const acknowledged = messages.value.find((message) => message.clientMsgId === clientMsgId)
    if (acknowledged) await cacheMessages([acknowledged]).catch(() => undefined)
    if (conversationId && Number.isFinite(sequence)) await recordPosition(conversationId, sequence)
    await outbox.remove(clientMsgId)
  }

  async function handleDelivery(envelope: WsEnvelope): Promise<void> {
    const delivered = normalizeMessage(envelope.payload as unknown as ChatMessage)
    if (!delivered.messageId || !delivered.conversationId) return
    await cacheMessages([delivered]).catch(() => undefined)
    if (delivered.sequence != null) await recordPosition(delivered.conversationId, delivered.sequence)
    if (belongsToSelected(delivered)) {
      mergeCurrentMessages([delivered])
      if (delivered.fromUserId !== currentUser.value?.id) {
        startBurnCountdown(delivered)
        sendReadPosition(delivered.conversationId, [delivered])
      }
    }
    updateConversationPreview(delivered)
    ws.sendEvent('CHAT_DELIVER', {
      messageId: delivered.messageId,
      sequence: delivered.sequence,
    }, {
      requestId: createRequestId(),
      clientMsgId: delivered.clientMsgId,
      conversationId: delivered.conversationId,
    })
  }

  async function handleSyncResponse(envelope: WsEnvelope): Promise<void> {
    const synced = Array.isArray(envelope.payload.messages)
      ? (envelope.payload.messages as unknown as ChatMessage[]).map(normalizeMessage)
      : []
    await cacheMessages(synced).catch(() => undefined)
    await recordReceivedPositions(synced)
    const selectedItems = synced.filter((message) => belongsToSelected(message))
    if (selectedItems.length > 0) {
      mergeCurrentMessages(selectedItems)
      scheduleBurnCountdowns(selectedItems)
    }
    synced.forEach(updateConversationPreview)

    const denied = Array.isArray(envelope.payload.deniedConversationIds)
      ? envelope.payload.deniedConversationIds.map(String)
      : []
    if (selected.value && denied.includes(selected.value.conversationId)) {
      toast.push('你已不在该会话，未发送消息将保留供处理', 'danger')
    }

    if (syncRequestId && envelope.requestId === syncRequestId) {
      finishSync(envelope.payload.hasMore === true)
    }
  }

  async function handleMessageMutation(envelope: WsEnvelope): Promise<void> {
    const messageId = String(envelope.payload.messageId || '')
    if (envelope.event === 'CHAT_BURN') clearBurnCountdown(messageId)
    const target = messages.value.find((message) => message.messageId === messageId)
    if (!target) return
    if (envelope.event === 'CHAT_RECALL') target.isRecalled = 1
    if (envelope.event === 'CHAT_BURN') {
      target.status = 2
      target.content = ''
      updateConversationPreview(target)
    }
    await cacheMessages([target]).catch(() => undefined)
  }

  function handleReadEvent(envelope: WsEnvelope): void {
    if (envelope.conversationId !== selected.value?.conversationId) return
    const readerId = Number(envelope.payload.userId)
    const lastReadSequence = Number(envelope.payload.lastReadSequence)
    if (!Number.isFinite(lastReadSequence) || readerId === currentUser.value?.id) return
    messages.value.forEach((message) => {
      if (message.fromUserId === currentUser.value?.id
        && message.sequence != null
        && message.sequence <= lastReadSequence) {
        message.deliveryState = 'READ'
        message.status = 1
      }
    })
  }

  async function synchronizeAfterReconnect(): Promise<void> {
    let hasMore = false
    for (let page = 0; page < 50; page += 1) {
      const positions = await loadPositions().catch(() => ({} as Record<string, number>))
      runtimePositions.forEach((sequence, conversationId) => {
        positions[conversationId] = Math.max(positions[conversationId] || 0, sequence)
      })
      conversations.value.forEach((conversation) => {
        if (conversation.conversationId && positions[conversation.conversationId] == null) {
          positions[conversation.conversationId] = 0
        }
      })
      hasMore = await requestSync(positions)
      if (!hasMore) break
    }
    if (hasMore) throw new Error('待同步消息较多，将在下次重连时继续补拉')
    await flushOutbox()
  }

  function requestSync(positions: Record<string, number>): Promise<boolean> {
    clearSyncWaiter()
    syncRequestId = createRequestId()
    return new Promise((resolve, reject) => {
      syncResolver = resolve
      syncRejecter = reject
      syncTimer = window.setTimeout(() => {
        syncRejecter?.(new Error('同步响应超时，消息仍保留在本地'))
        clearSyncWaiter()
      }, 12_000)
      if (!ws.sendEvent('SYNC_REQUEST', { positions, limit: 100 }, { requestId: syncRequestId! })) {
        reject(new Error('连接尚未完成认证'))
        clearSyncWaiter()
      }
    })
  }

  function finishSync(hasMore: boolean): void {
    const resolve = syncResolver
    clearSyncWaiter()
    resolve?.(hasMore)
  }

  function clearSyncWaiter(): void {
    if (syncTimer !== null) window.clearTimeout(syncTimer)
    syncTimer = null
    syncResolver = null
    syncRejecter = null
    syncRequestId = null
  }

  async function sendText(
    content: string,
    options: { burn?: boolean; replyToId?: string } = {},
  ): Promise<boolean> {
    const conversation = selected.value
    if (!conversation || !content.trim()) return false
    const payload: ChatSendPayload = {
      contentType: 'text',
      content: content.trim(),
      isBurn: Boolean(options.burn),
      replyToId: options.replyToId || null,
      [conversation.kind === 'group' ? 'groupId' : 'toUserId']: conversation.id,
    }
    await queueMessage(conversation, payload)
    return true
  }

  async function sendFile(file: File): Promise<void> {
    const conversation = selected.value
    if (!conversation) return
    if (!ws.connected.value) throw new Error('文件需要连接节点后上传；文本消息仍可离线发送')
    const uploaded = await api.files.upload(file, conversation.conversationId)
    const image = file.type.startsWith('image/')
    const content = fileContent(uploaded, file, image)
    const payload: ChatSendPayload = {
      contentType: image ? 'image' : 'file',
      content,
      isBurn: false,
      [conversation.kind === 'group' ? 'groupId' : 'toUserId']: conversation.id,
    }
    await queueMessage(conversation, payload)
  }

  function fileContent(uploaded: FileUpload, file: File, image: boolean): string {
    return image
      ? JSON.stringify({
          url: uploaded.url,
          thumbnailUrl: uploaded.thumbnailUrl,
          originalUrl: uploaded.url,
        })
      : JSON.stringify({
          name: uploaded.originalName || file.name,
          size: uploaded.fileSize,
          url: uploaded.url,
        })
  }

  async function queueMessage(conversation: Conversation, payload: ChatSendPayload): Promise<void> {
    const user = currentUser.value
    if (!user) throw new Error('登录状态已失效')
    const conversationId = conversation.conversationId || resolveConversationId(conversation, user.id)
    const clientMsgId = createClientMessageId()
    const entry: OutboxEntry = {
      clientMsgId,
      requestId: createRequestId(),
      conversationId,
      payload,
      createdAt: new Date().toISOString(),
      retryCount: 0,
      state: 'WAITING_NETWORK',
    }
    await outbox.enqueue(entry)
    if (!outbox.durable.value && !warnedAboutVolatileOutbox) {
      warnedAboutVolatileOutbox = true
      toast.push('本地持久化暂不可用；请保持页面开启，消息仍会在连接恢复后发送', 'warning', 4200)
    }
    const optimistic = optimisticMessage(entry, user)
    if (selected.value?.conversationId === conversationId) mergeCurrentMessages([optimistic])
    await cacheMessages([optimistic]).catch(() => undefined)
    updateConversationPreview(optimistic)
    void flushOutbox()
  }

  function optimisticMessage(entry: OutboxEntry, user: User): ChatMessage {
    return normalizeMessage({
      messageId: `local:${entry.clientMsgId}`,
      clientMsgId: entry.clientMsgId,
      conversationId: entry.conversationId,
      fromUserId: user.id,
      fromNickname: user.nickname,
      fromAvatar: user.avatar,
      toUserId: typeof entry.payload.toUserId === 'number' ? entry.payload.toUserId : undefined,
      groupId: typeof entry.payload.groupId === 'number' ? entry.payload.groupId : undefined,
      type: entry.payload.contentType,
      contentType: entry.payload.contentType,
      content: entry.payload.content,
      replyToId: typeof entry.payload.replyToId === 'string' ? entry.payload.replyToId : undefined,
      isBurn: entry.payload.isBurn ? 1 : 0,
      status: 0,
      clientCreatedAt: entry.createdAt,
      createTime: entry.createdAt,
      deliveryState: entry.state,
      errorMessage: entry.lastError,
    })
  }

  async function flushOutbox(): Promise<void> {
    if (flushPromise) return flushPromise
    flushPromise = (async () => {
      while (ws.connected.value) {
        const entry = outbox.readyEntries()[0]
        if (!entry) break
        const retryCount = entry.retryCount + 1
        await outbox.update(entry.clientMsgId, {
          state: 'SENDING',
          retryCount,
          lastError: undefined,
        })
        updateDeliveryState(entry.clientMsgId, 'SENDING')

        const sent = ws.sendEvent('CHAT_SEND', entry.payload, {
          requestId: entry.requestId,
          clientMsgId: entry.clientMsgId,
          conversationId: entry.conversationId,
        })
        if (!sent) {
          await outbox.update(entry.clientMsgId, { state: 'WAITING_NETWORK' })
          updateDeliveryState(entry.clientMsgId, 'WAITING_NETWORK')
          break
        }

        const outcome = await waitForAck(entry.clientMsgId, 10_000)
        if (outcome === 'ERROR') continue
        if (outcome === 'ACK') continue

        if (retryCount >= 3) {
          const message = '多次未收到服务端确认，可手动重试'
          await outbox.update(entry.clientMsgId, { state: 'FAILED', lastError: message })
          updateDeliveryState(entry.clientMsgId, 'FAILED', message)
          continue
        }
        await outbox.update(entry.clientMsgId, { state: 'WAITING_NETWORK', lastError: 'ACK 超时，正在重试' })
        updateDeliveryState(entry.clientMsgId, 'WAITING_NETWORK')
      }
    })().finally(() => { flushPromise = null })
    return flushPromise
  }

  function waitForAck(clientMsgId: string, timeout: number): Promise<AckOutcome | 'TIMEOUT'> {
    return new Promise((resolve) => {
      const timer = window.setTimeout(() => {
        ackWaiters.delete(clientMsgId)
        resolve('TIMEOUT')
      }, timeout)
      ackWaiters.set(clientMsgId, { resolve, timer })
    })
  }

  function settleAck(clientMsgId: string, outcome: AckOutcome): void {
    const waiter = ackWaiters.get(clientMsgId)
    if (!waiter) return
    window.clearTimeout(waiter.timer)
    ackWaiters.delete(clientMsgId)
    waiter.resolve(outcome)
  }

  function updateDeliveryState(
    clientMsgId: string,
    deliveryState: MessageDeliveryState,
    errorMessage?: string,
  ): void {
    messages.value = messages.value.map((message) => message.clientMsgId === clientMsgId
      ? { ...message, deliveryState, errorMessage }
      : message)
  }

  async function retryOutbox(): Promise<void> {
    await outbox.retryFailed()
    messages.value = messages.value.map((message) => message.deliveryState === 'FAILED'
      ? { ...message, deliveryState: 'WAITING_NETWORK', errorMessage: undefined }
      : message)
    if (!ws.connected.value) ws.reconnect()
    void flushOutbox()
  }

  async function retryMessage(clientMsgId: string): Promise<void> {
    await outbox.update(clientMsgId, { state: 'WAITING_NETWORK', lastError: undefined })
    updateDeliveryState(clientMsgId, 'WAITING_NETWORK')
    if (!ws.connected.value) ws.reconnect()
    void flushOutbox()
  }

  async function cancelPendingMessage(clientMsgId: string): Promise<void> {
    const entry = outbox.entries.value.find((item) => item.clientMsgId === clientMsgId)
    if (!entry) return
    if (entry.state === 'SENDING') {
      toast.push('消息结果仍在确认中，暂不能取消', 'warning')
      return
    }
    await outbox.remove(clientMsgId)
    await deleteCachedMessagesByClientMsgId(clientMsgId).catch(() => undefined)
    messages.value = messages.value.filter((message) => message.clientMsgId !== clientMsgId)
  }

  function sendTyping(): void {
    const conversation = selected.value
    if (!conversation || !ws.connected.value) return
    ws.sendEvent('TYPING_START', {}, {
      requestId: createRequestId(),
      conversationId: conversation.conversationId,
    })
  }

  function recall(messageId: string): void {
    const conversationId = selected.value?.conversationId
    if (!conversationId || !ws.sendEvent('CHAT_RECALL', { messageId }, {
      requestId: createRequestId(),
      conversationId,
    })) toast.push('连接已断开，暂时无法撤回', 'warning')
  }

  function burn(messageId: string): void {
    requestBurn(messageId, true)
  }

  function scheduleBurnCountdowns(source: readonly ChatMessage[]): void {
    source.forEach(startBurnCountdown)
  }

  function startBurnCountdown(message: ChatMessage): void {
    if (message.fromUserId === currentUser.value?.id
      || Number(message.isBurn) !== 1
      || message.isRecalled === 1
      || message.status === 2
      || !message.messageId
      || message.messageId.startsWith('local:')) return
    if (burnTimers.has(message.messageId)) return

    const conversationId = message.conversationId || selected.value?.conversationId
    if (!conversationId) return
    burnConversationIds.set(message.messageId, conversationId)
    const seconds = Math.min(60, Math.max(1, Number(message.burnDuration) || 5))
    burnTimers.set(message.messageId, window.setTimeout(() => {
      burnTimers.delete(message.messageId)
      if (!shouldContinueBurn(message.messageId)) return
      if (!requestBurn(message.messageId, false)) scheduleBurnRetry(message.messageId)
    }, seconds * 1000))
  }

  function scheduleBurnRetry(messageId: string): void {
    if (!burnConversationIds.has(messageId) || burnTimers.has(messageId)) return
    burnTimers.set(messageId, window.setTimeout(() => {
      burnTimers.delete(messageId)
      if (!shouldContinueBurn(messageId)) return
      if (!requestBurn(messageId, false)) scheduleBurnRetry(messageId)
    }, 1_500))
  }

  function shouldContinueBurn(messageId: string): boolean {
    if (!burnConversationIds.has(messageId)) return false
    const target = messages.value.find((message) => message.messageId === messageId)
    if (target && (target.status === 2 || target.isRecalled === 1)) {
      clearBurnCountdown(messageId)
      return false
    }
    return true
  }

  function clearBurnCountdown(messageId: string): void {
    const timer = burnTimers.get(messageId)
    if (timer !== undefined) window.clearTimeout(timer)
    burnTimers.delete(messageId)
    burnConversationIds.delete(messageId)
  }

  function requestBurn(messageId: string, notifyOnFailure: boolean): boolean {
    const target = messages.value.find((message) => message.messageId === messageId)
    const conversationId = target?.conversationId
      || burnConversationIds.get(messageId)
      || selected.value?.conversationId
    if (!conversationId || !ws.sendEvent('CHAT_BURN', { messageId }, {
      requestId: createRequestId(),
      conversationId,
    })) {
      if (notifyOnFailure) toast.push('连接已断开，暂时无法焚毁', 'warning')
      return false
    }

    clearBurnCountdown(messageId)
    if (target) {
      target.status = 2
      target.content = ''
      updateConversationPreview(target)
      void cacheMessages([target]).catch(() => undefined)
    }
    return true
  }

  function sendReadPosition(conversationId: string, source: readonly ChatMessage[]): void {
    const lastReadSequence = source.reduce(
      (maximum, message) => Math.max(maximum, message.sequence || 0),
      0,
    )
    if (lastReadSequence <= 0 || !ws.connected.value) return
    ws.sendEvent('CHAT_READ', { lastReadSequence }, {
      requestId: createRequestId(),
      conversationId,
    })
  }

  async function recordReceivedPositions(source: readonly ChatMessage[]): Promise<void> {
    const maximums = new Map<string, number>()
    source.forEach((message) => {
      if (!message.conversationId || message.sequence == null) return
      maximums.set(message.conversationId, Math.max(
        maximums.get(message.conversationId) || 0,
        message.sequence,
      ))
    })
    await Promise.all([...maximums].map(([conversationId, sequence]) =>
      recordPosition(conversationId, sequence)))
  }

  async function recordPosition(conversationId: string, sequence: number): Promise<void> {
    runtimePositions.set(conversationId, Math.max(
      runtimePositions.get(conversationId) || 0,
      sequence,
    ))
    await savePosition(conversationId, sequence).catch(() => undefined)
  }

  function mergeCurrentMessages(incoming: readonly ChatMessage[]): void {
    messages.value = mergeMessages(messages.value, incoming)
  }

  function mergeMessages(
    current: readonly ChatMessage[],
    incoming: readonly ChatMessage[],
  ): ChatMessage[] {
    const merged: ChatMessage[] = []
    ;[...current, ...incoming].forEach((raw) => {
      const next = normalizeMessage(raw)
      const index = merged.findIndex((item) =>
        (next.messageId && item.messageId === next.messageId)
        || (next.clientMsgId && item.clientMsgId === next.clientMsgId))
      if (index < 0) merged.push(next)
      else merged[index] = normalizeMessage({ ...merged[index], ...next })
    })
    return sortMessages(merged)
  }

  function sortMessages(source: readonly ChatMessage[]): ChatMessage[] {
    return [...source].sort((first, second) => {
      if (first.sequence != null && second.sequence != null) return first.sequence - second.sequence
      if (first.sequence != null) return -1
      if (second.sequence != null) return 1
      return new Date(first.clientCreatedAt || first.createTime || 0).getTime()
        - new Date(second.clientCreatedAt || second.createTime || 0).getTime()
    })
  }

  function updateConversationPreview(message: ChatMessage): void {
    const previewContent = message.status === 2 ? '消息已焚毁' : message.content
    if (message.groupId) {
      const group = groups.value.find((item) => item.id === message.groupId)
      if (group) {
        group.lastMessage = previewContent
        group.lastMessageType = message.type
        group.lastMessageTime = message.createTime
        persistConversationDirectory()
      }
      return
    }
    const me = currentUser.value?.id
    const otherId = message.fromUserId === me ? message.toUserId : message.fromUserId
    const friend = friends.value.find((item) => item.friendId === otherId)
    if (friend) {
      friend.lastMessage = previewContent
      friend.lastMessageType = message.type
      friend.lastMessageTime = message.createTime
      persistConversationDirectory()
    }
  }

  function persistConversationDirectory(): void {
    void saveConversationDirectory(friends.value, groups.value).catch(() => undefined)
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
    const updated = conversations.value.find(
      (conversation) => conversation.kind === 'private' && conversation.id === selected.value?.id,
    )
    if (updated) selected.value = updated
  }

  return {
    friends: readonly(friends),
    groups: readonly(groups),
    requests: readonly(requests),
    members: readonly(members),
    messages: readonly(messages),
    messageSearchResults: readonly(messageSearchResults),
    messageSearchLoading: readonly(messageSearchLoading),
    messageSearchError: readonly(messageSearchError),
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
    connectionState: ws.state,
    reconnectAttempts: ws.reconnectAttempts,
    latencyMs: ws.latencyMs,
    lastHeartbeatAt: ws.lastHeartbeatAt,
    lastSyncAt: ws.lastSyncAt,
    pendingCount: outbox.pendingCount,
    failedCount: outbox.failedCount,
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
    retryOutbox,
    retryMessage,
    cancelPendingMessage,
    reconnect: ws.reconnect,
    disconnect: ws.disconnect,
  }
}

function createRequestId(): string {
  return `req_${createClientMessageId()}`
}
