import { computed, onBeforeUnmount, readonly, ref, shallowRef, watch } from 'vue'
import { navigateToApp } from '../platform/appNavigation'
import { nativeBridge } from '../platform/nativeBridge'
import { selectedNode } from '../platform/nodeContext'
import { api } from '../services/api'
import {
  applyConversationMessage,
  applyConversationRead,
  conversationReadRequiresSnapshot,
  indexConversationSummaries,
  removeConversationSummary,
} from '../services/conversationSummaryState'
import type {
  ConversationMessageDelta,
  ConversationReadDelta,
  ConversationSummaryMap,
} from '../services/conversationSummaryState'
import { ConversationSelectionGeneration } from '../services/conversationSelectionState'
import { playNotificationSound } from '../services/notificationSound'
import { publishRealtimeEvent } from '../services/realtimeEvents'
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
  ConversationSummary,
  FileAttachmentData,
  Friend,
  FriendRequest,
  GroupMember,
  MessageDeliveryState,
  OutboxEntry,
  TemporaryRoom,
  User,
  WsEnvelope,
} from '../types'
import { resolveConversationId, groupConversationId, privateConversationId } from '../utils/conversation'
import { conversationPreview } from '../utils/format'
import { createClientMessageId } from '../utils/id'
import { advanceContiguousSequence } from '../utils/sequence'
import { clearCacheOwner, clearSession } from '../utils/storage'
import { useAuth } from './useAuth'
import { useFileTransferSettings } from './useFileTransferSettings'
import { useOutbox } from './useOutbox'
import { usePeerFileTransfer } from './usePeerFileTransfer'
import { useResumableUpload } from './useResumableUpload'
import { useToast } from './useToast'
import { useWebSocket } from './useWebSocket'

export type ChatSection = 'messages' | 'contacts' | 'groups' | 'broadcasts' | 'admin'

type AckOutcome = 'ACK' | 'ERROR'

interface AckWaiter {
  resolve: (outcome: AckOutcome | 'TIMEOUT') => void
  timer: number
}

type ConversationSummaryDelta =
  | { kind: 'message'; value: ConversationMessageDelta }
  | { kind: 'read'; value: ConversationReadDelta }
  | { kind: 'remove'; conversationId: string }

interface VersionedConversationSummaryDelta {
  revision: number
  delta: ConversationSummaryDelta
}

export function useChat() {
  const auth = useAuth()
  const { currentUser } = auth
  const toast = useToast()
  const fileTransferSettings = useFileTransferSettings()
  const outbox = useOutbox()
  const friends = ref<Friend[]>([])
  const groups = ref<ChatGroup[]>([])
  const rooms = ref<TemporaryRoom[]>([])
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
  const relayTransferLabel = shallowRef('')
  const conversationSummaries = shallowRef<ConversationSummaryMap>({})

  const ackWaiters = new Map<string, AckWaiter>()
  const burnTimers = new Map<string, number>()
  const burnConversationIds = new Map<string, string>()
  const runtimePositions = new Map<string, number>()
  const pendingReadPositions = new Map<string, number>()
  const inaccessibleConversationIds = new Set<string>()
  const summaryDeltaLog: VersionedConversationSummaryDelta[] = []
  const conversationSelection = new ConversationSelectionGeneration()
  let flushPromise: Promise<void> | null = null
  let conversationSummaryRefreshPromise: Promise<void> | null = null
  let summaryRevision = 0
  let warnedAboutVolatileOutbox = false
  let syncResolver: ((hasMore: boolean) => void) | null = null
  let syncRejecter: ((cause: Error) => void) | null = null
  let syncRequestId: string | null = null
  let syncTimer: number | null = null
  let messageSearchTimer: number | null = null
  let messageSearchSequence = 0
  let backgroundSyncTimer: number | null = null
  let synchronizationPromise: Promise<void> | null = null

  onBeforeUnmount(() => {
    burnTimers.forEach((timer) => window.clearTimeout(timer))
    burnTimers.clear()
    burnConversationIds.clear()
    conversationSelection.invalidate()
    if (messageSearchTimer !== null) window.clearTimeout(messageSearchTimer)
    if (backgroundSyncTimer !== null) window.clearInterval(backgroundSyncTimer)
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

  function getConversationSummary(conversationId: string): ConversationSummary | undefined {
    return conversationSummaries.value[conversationId]
  }

  function applySummaryDelta(
    source: ConversationSummaryMap,
    delta: ConversationSummaryDelta,
  ): ConversationSummaryMap {
    if (delta.kind === 'message') return applyConversationMessage(source, delta.value)
    if (delta.kind === 'read') return applyConversationRead(source, delta.value)
    return removeConversationSummary(source, delta.conversationId)
  }

  function recordSummaryDelta(delta: ConversationSummaryDelta): void {
    summaryRevision += 1
    if (conversationSummaryRefreshPromise) {
      summaryDeltaLog.push({ revision: summaryRevision, delta })
    }
    conversationSummaries.value = applySummaryDelta(conversationSummaries.value, delta)
    void persistConversationDirectory()
  }

  function forgetConversation(conversationId: string): void {
    if (!conversationId) return
    inaccessibleConversationIds.add(conversationId)
    recordSummaryDelta({ kind: 'remove', conversationId })
  }

  const conversations = computed<Conversation[]>(() => {
    const me = currentUser.value?.id

    const privateChats = friends.value.map((friend): Conversation => {
      const conversationId = me ? privateConversationId(me, friend.friendId) : ''
      const summary = getConversationSummary(conversationId)

      const lastMessage = summary?.lastMessage !== undefined
          ? summary.lastMessage
          : friend.lastMessage

      const lastMessageType = summary?.lastMessageType !== undefined
          ? summary.lastMessageType
          : friend.lastMessageType

      return {
        id: friend.friendId,
        conversationId,
        kind: 'private',
        name: friend.remark || friend.nickname,
        avatar: friend.avatar,
        subtitle: friend.signature,
        lastMessage: conversationPreview(lastMessageType, lastMessage),
        lastMessageType,
        lastMessageTime: summary?.lastMessageAt || friend.lastMessageTime,
        online: onlineIds.value.has(friend.friendId) || friend.online === 1,
        pinned: summary?.pinned ?? friend.isPinned === 1,
        muted: summary?.muted ?? friend.isMuted === 1,
        unreadCount: summary?.unreadCount || 0,
        lastSequence: summary?.lastSequence || 0,
        lastReadSequence: summary?.lastReadSequence || 0,
        pendingCount: outbox.entries.value.filter(
            (entry) => entry.conversationId === conversationId,
        ).length,
        source: friend,
      }
    })

    const groupChats = groups.value.map((group): Conversation => {
      const conversationId = groupConversationId(group.id)
      const summary = getConversationSummary(conversationId)

      const lastMessage = summary?.lastMessage !== undefined
          ? summary.lastMessage
          : group.lastMessage

      const lastMessageType = summary?.lastMessageType !== undefined
          ? summary.lastMessageType
          : group.lastMessageType

      return {
        id: group.id,
        conversationId,
        kind: 'group',
        name: group.groupName,
        avatar: group.avatar,
        subtitle: group.announcement,
        lastMessage: conversationPreview(lastMessageType, lastMessage),
        lastMessageType,
        lastMessageTime: summary?.lastMessageAt || group.lastMessageTime,
        pinned: summary?.pinned || false,
        muted: summary?.muted || false,
        unreadCount: summary?.unreadCount || 0,
        lastSequence: summary?.lastSequence || 0,
        lastReadSequence: summary?.lastReadSequence || 0,
        pendingCount: outbox.entries.value.filter(
            (entry) => entry.conversationId === conversationId,
        ).length,
        source: group,
      }
    })

    const temporaryChats = rooms.value.map((room): Conversation => {
      const summary = getConversationSummary(room.conversationId)
      const hasSummaryMessage = summary?.lastMessage !== undefined

      return {
        id: room.id,
        conversationId: room.conversationId,
        kind: 'temporary',
        name: room.roomName,
        subtitle: room.purpose || temporaryRoomStatusLabel(room.status),
        lastMessage: hasSummaryMessage
            ? conversationPreview(summary.lastMessageType, summary.lastMessage)
            : temporaryRoomStatusLabel(room.status),
        lastMessageType: summary?.lastMessageType,
        lastMessageTime: summary?.lastMessageAt || room.updateTime || room.createTime,
        pinned: summary?.pinned || false,
        muted: summary?.muted || false,
        unreadCount: summary?.unreadCount || 0,
        lastSequence: summary?.lastSequence || 0,
        lastReadSequence: summary?.lastReadSequence || 0,
        pendingCount: outbox.entries.value.filter(
            (entry) => entry.conversationId === room.conversationId,
        ).length,
        source: room,
      }
    })

    return [...privateChats, ...groupChats, ...temporaryChats]
        .sort((first, second) => {
          if (first.pinned !== second.pinned) {
            return first.pinned ? -1 : 1
          }

          return new Date(second.lastMessageTime || 0).getTime()
              - new Date(first.lastMessageTime || 0).getTime()
        })
  })

  const totalUnreadCount = computed(() =>
      conversations.value.reduce(
          (total, conversation) => total + (conversation.unreadCount || 0),
          0,
      ),
  )

  const visibleConversations = computed(() => {
    const base = section.value === 'contacts'
      ? conversations.value.filter((item) => item.kind === 'private')
      : section.value === 'groups'
        ? conversations.value.filter((item) => item.kind === 'group' || item.kind === 'temporary')
        : section.value === 'admin' || section.value === 'broadcasts'
          ? []
          : conversations.value
    const needle = query.value.trim().toLocaleLowerCase('zh-CN')
    return needle
      ? base.filter((item) => item.name.toLocaleLowerCase('zh-CN').includes(needle))
      : base
  })

  const ws = useWebSocket({
    onMessage: handleSocketMessage,
    onReady: async () => {
      await refreshConversationSummaries()
      await synchronizeAfterReconnect()
      try {
        await Promise.all([
          refreshLists({ includeSummaries: false }),
          auth.hydrate(),
        ])
      } catch {
        toast.push('好友或账号状态刷新失败，请稍后重试', 'warning')
      }
    },
    // synchronizeAfterReconnect finishes while the socket still reports
    // SYNCING, so its defensive flush is intentionally skipped. Retry once the
    // connection has actually transitioned to ONLINE.
    onOnline: () => {
      flushPendingReadPositions()
      return flushOutbox()
    },
    onError: (message) => toast.push(message, 'warning'),
    refreshAuth: api.auth.refreshSession,
    onAuthFailed: (reason) => {
      clearSession()
      if (reason === 'FORCE_LOGOUT') {
        void clearLocalChatDatabase()
          .then(() => clearCacheOwner())
          .catch(() => undefined)
          .finally(() => navigateToApp('/', true))
        return
      }
      // Refresh 过期不应销毁离线发件箱；同一用户重新登录后继续补发，
      // 若改用其他账号，prepareLocalCache 会按 owner 清理隔离数据。
      navigateToApp('/', true)
    },
  })
  const peerFiles = usePeerFileTransfer({
    sendEvent: (event, payload, metadata) => ws.sendEvent(event, payload, metadata),
  })
  const resumableFiles = useResumableUpload()
  backgroundSyncTimer = window.setInterval(() => {
    if (ws.connected.value) void refreshAndSynchronize().catch(() => undefined)
  }, 60_000)
  const fileTransferLabel = computed(() => {
    if (resumableFiles.phase.value === 'HASHING') return '正在校验中转文件完整性…'
    if (resumableFiles.phase.value === 'UPLOADING') {
      return `节点断点续传 ${resumableFiles.progress.value}%`
    }
    if (resumableFiles.phase.value === 'COMPLETING') return '节点正在合并并复核文件…'
    if (relayTransferLabel.value) return relayTransferLabel.value
    if (peerFiles.phase.value === 'HASHING') return '正在校验文件完整性…'
    if (peerFiles.phase.value === 'NEGOTIATING') return '正在协商局域网设备直传…'
    if (peerFiles.phase.value === 'TRANSFERRING') return `设备直传 ${peerFiles.progress.value}%`
    if (peerFiles.phase.value === 'VERIFYING') return '对端正在校验文件…'
    return ''
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
        rooms.value = cachedDirectory.rooms || []
        conversationSummaries.value =
          indexConversationSummaries(cachedDirectory.summaries || [])
      }
      try {
        const [friendList, groupList, roomList, requestList, summaryList] = await Promise.all([
          api.friends.list(),
          api.groups.list(),
          api.rooms.list(),
          api.friends.requests(),
          api.chat.conversations(),
        ])
        friends.value = friendList || []
        groups.value = groupList || []
        rooms.value = roomList || []
        requests.value = await enrichRequests(requestList || [])
        conversationSummaries.value = indexConversationSummaries(summaryList || [])
        await persistConversationDirectory()
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

  async function refreshLists(
    options: { includeSummaries?: boolean } = {},
  ): Promise<void> {
    const includeSummaries = options.includeSummaries !== false
    const [friendList, groupList, roomList, requestList] = await Promise.all([
      api.friends.list(),
      api.groups.list(),
      api.rooms.list(),
      api.friends.requests(),
    ])
    friends.value = friendList || []
    groups.value = groupList || []
    rooms.value = roomList || []
    requests.value = await enrichRequests(requestList || [])
    if (includeSummaries) await refreshConversationSummaries()
    await persistConversationDirectory()
  }

  function refreshConversationSummaries(): Promise<void> {
    if (conversationSummaryRefreshPromise) return conversationSummaryRefreshPromise
    const startedAtRevision = summaryRevision
    conversationSummaryRefreshPromise = (async () => {
      const snapshot = await api.chat.conversations()
      let next = indexConversationSummaries(snapshot || [])
      summaryDeltaLog
        .filter((entry) => entry.revision > startedAtRevision)
        .forEach((entry) => {
          next = applySummaryDelta(next, entry.delta)
        })
      conversationSummaries.value = next
      Object.keys(next).forEach((conversationId) => {
        inaccessibleConversationIds.delete(conversationId)
      })
    })().finally(() => {
      summaryDeltaLog.splice(0, summaryDeltaLog.length)
      conversationSummaryRefreshPromise = null
    })
    return conversationSummaryRefreshPromise
  }

  async function refreshConversationSummariesAfterCurrent(): Promise<void> {
    const activeRefresh = conversationSummaryRefreshPromise
    if (activeRefresh) await activeRefresh.catch(() => undefined)
    await refreshConversationSummaries()
  }

  async function selectConversation(conversation: Conversation): Promise<void> {
    const user = currentUser.value
    if (!user) throw new Error('登录状态已失效')
    const conversationId = conversation.conversationId || resolveConversationId(conversation, user.id)
    const selectionToken = conversationSelection.begin(conversationId)
    const isCurrentSelection = () => conversationSelection.isCurrent(
      selectionToken,
      selected.value?.conversationId,
    )
    selected.value = { ...conversation, conversationId, unreadCount: 0 }
    loadingMessages.value = true
    typingLabel.value = ''
    messages.value = []
    members.value = []

    try {
      const cached = await loadCachedMessages(conversationId)
        .then((items) => items.map(normalizeMessage))
        .catch(() => [] as ChatMessage[])
      const optimistic = outbox.entries.value
        .filter((entry) => entry.conversationId === conversationId)
        .map((entry) => optimisticMessage(entry, user))
      if (!isCurrentSelection()) return
      messages.value = mergeMessages(cached, optimistic)
      scheduleBurnCountdowns(messages.value)
      await recordReceivedPositions(cached)
      if (!isCurrentSelection()) return

      const history = await api.chat.history(conversationId)
      const normalized = (history || []).map(normalizeMessage)
      const nextMessages = mergeMessages(normalized, optimistic)
      await cacheMessages(nextMessages).catch(() => undefined)
      await recordReceivedPositions(normalized)
      const nextMembers = conversation.kind === 'group'
        ? await api.groups.members(conversation.id)
        : []
      if (!isCurrentSelection()) return
      messages.value = nextMessages
      scheduleBurnCountdowns(messages.value)
      members.value = nextMembers
      sendReadPosition(conversationId, normalized)
    } catch {
      if (!isCurrentSelection()) return
      const nextMembers = conversation.kind === 'group'
        ? await api.groups.members(conversation.id).catch(() => [])
        : []
      if (!isCurrentSelection()) return
      members.value = nextMembers
      toast.push('当前使用本地缓存，连接恢复后会自动同步', 'warning', 2600)
    } finally {
      if (conversationSelection.owns(selectionToken)) loadingMessages.value = false
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
    if (envelope.event.startsWith('FILE_TRANSFER_')) {
      await publishRealtimeEvent(envelope)
      return
    }
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
      case 'PROFILE_UPDATED':
        handleProfileUpdated(envelope)
        return
      case 'BROADCAST_PERMISSION_UPDATED':
        auth.applyBroadcastPermission(envelope.payload.enabled === true)
        toast.push(
          envelope.payload.enabled === true
            ? '管理员已授予你广播发布权限'
            : '管理员已撤销你的广播发布权限',
          'success',
        )
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
      case 'SYNC_REQUIRED':
        // This handler itself runs inside the serialized inbound queue. Do not
        // await a task whose SYNC_RESPONSE must be processed by that same queue.
        void refreshAndSynchronize().catch(() => undefined)
        return
      case 'CHAT_RECALL':
      case 'CHAT_BURN':
        await handleMessageMutation(envelope)
        return
      case 'CHAT_READ':
        handleReadEvent(envelope)
        return
      case 'CONVERSATION_REMOVED':
        handleConversationRemoved(envelope)
        return
      case 'CONVERSATION_CHANGED':
        await refreshConversationSummaries()
        return
      case 'BROADCAST':
      case 'BROADCAST_UPDATED':
      case 'ROOM_STATUS_CHANGED':
        await publishRealtimeEvent(envelope)
        if (envelope.event === 'ROOM_STATUS_CHANGED') await refreshLists()
        return
      default:
        // V1 客户端必须忽略未知但非致命事件，以便服务端向前兼容。
    }
  }

  function handleProfileUpdated(envelope: WsEnvelope): void {
    const userId = Number(envelope.payload.userId)
    const nickname = String(envelope.payload.nickname || '')
    const avatar = String(envelope.payload.avatar || '')
    if (!Number.isFinite(userId)) return

    // 更新好友列表中的头像和昵称
    friends.value = friends.value.map((friend) => {
      if (friend.friendId !== userId) return friend
      return {
        ...friend,
        nickname: nickname || friend.nickname,
        avatar,
      }
    })

    // 当前正在和这个用户聊天时，同步更新聊天标题头像
    if (selected.value?.kind === 'private' && selected.value.id === userId) {
      const friend = friends.value.find((item) => item.friendId === userId,)
      selected.value = {
        ...selected.value,
        name: friend?.remark || nickname || selected.value.name,
        avatar,
        source: friend || selected.value.source,
      }
    }

    // 更新群成员列表头像
    members.value = members.value.map((member) => {
      if (member.userId !== userId) return member
      return {
        ...member,
        nickname: nickname || member.nickname,
        avatar,
      }
    })
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
    const previousSequence = conversationId
      ? runtimePositions.get(conversationId) || 0
      : 0
    const hasSequenceGap = Boolean(conversationId)
      && Number.isFinite(sequence)
      && sequence > previousSequence + 1

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
    if (acknowledged) {
      updateConversationPreview(acknowledged, belongsToSelected(acknowledged))
      await cacheMessages([acknowledged]).catch(() => undefined)
    }
    if (conversationId && Number.isFinite(sequence) && !hasSequenceGap) {
      await recordPosition(conversationId, sequence)
    }
    await outbox.remove(clientMsgId)
    if (hasSequenceGap) void refreshAndSynchronize().catch(() => undefined)
  }

  async function handleDelivery(envelope: WsEnvelope): Promise<void> {
    const delivered = normalizeMessage(envelope.payload as unknown as ChatMessage)
    if (!delivered.messageId || !delivered.conversationId) return
    const conversationId = delivered.conversationId
    if (inaccessibleConversationIds.has(conversationId)) return
    const isCurrentConversation = belongsToSelected(delivered)
    const isIncomingMessage = delivered.fromUserId !== currentUser.value?.id
    const conversation =  conversations.value.find((item) => item.conversationId === conversationId)
    const previousSequence = runtimePositions.get(delivered.conversationId) || 0
    const hasSequenceGap = delivered.sequence != null
      && delivered.sequence > previousSequence + 1
    await cacheMessages([delivered]).catch(() => undefined)
    // A high sequence is not a contiguous receive position. Advancing it before
    // SYNC_REQUEST would make the server skip the missing range permanently.
    if (delivered.sequence != null && !hasSequenceGap) {
      await recordPosition(conversationId, delivered.sequence)
    }
    updateConversationPreview(delivered, isCurrentConversation)
    if (isCurrentConversation) {
      mergeCurrentMessages([delivered])
      if (isIncomingMessage) {
        startBurnCountdown(delivered)
        if (!hasSequenceGap) {
          sendReadPosition(conversationId, [delivered])
        }
      }
    } else if (isIncomingMessage) {
      if (!conversation?.muted) {
        const title = conversation?.name || delivered.fromNickname || '收到新消息'
        const preview = conversationPreview(delivered.type || delivered.contentType, delivered.content)
        toast.push(
            preview ? `${title}: ${preview}` : `${title}发来一条新消息`,
            'default',
            2800,
        )
        playNotificationSound()
      }
    }
    if (isIncomingMessage
      && !conversation?.muted
      && nativeBridge.runtime() !== 'web'
      && (document.visibilityState !== 'visible' || !document.hasFocus())) {
      const title = conversation?.name || delivered.fromNickname || 'MeshX 新消息'
      const preview = conversationPreview(delivered.type || delivered.contentType, delivered.content)
      void nativeBridge.notify({
        title,
        body: (preview || '发来一条新消息').replace(/\s+/g, ' ').slice(0, 160),
        target: {
          kind: 'conversation',
          value: conversationId,
          nodeOrigin: selectedNode()?.origin,
        },
      }).catch(() => undefined)
    }
    ws.sendEvent('CHAT_DELIVER', {
      messageId: delivered.messageId,
      sequence: delivered.sequence,
    }, {
      requestId: createRequestId(),
      clientMsgId: delivered.clientMsgId,
      conversationId: delivered.conversationId,
    })
    if (hasSequenceGap) void refreshAndSynchronize().catch(() => undefined)
  }

  async function handleSyncResponse(envelope: WsEnvelope): Promise<void> {
    const synced = Array.isArray(envelope.payload.messages)
      ? (envelope.payload.messages as unknown as ChatMessage[]).map(normalizeMessage)
      : []
    await cacheMessages(synced).catch(() => undefined)
    // SYNC_RESPONSE is an authoritative ascending query from the cursor sent
    // to the server. Its maximum returned sequence is safe even when physical
    // message deletion has left a permanent sequence gap. When a deleted tail
    // produces no rows, latestPositions is the authoritative cursor. Cache and
    // history pages do not have either guarantee and stay contiguous below.
    await recordSynchronizedPositions(synced, envelope.payload.latestPositions)
    let synchronizedUnreadCount = 0
    synced.forEach((message) => {
      if (!message.conversationId
        || inaccessibleConversationIds.has(message.conversationId)) return
      const before = getConversationSummary(message.conversationId)?.unreadCount || 0
      updateConversationPreview(message, belongsToSelected(message))
      const after = getConversationSummary(message.conversationId)?.unreadCount || 0
      synchronizedUnreadCount += Math.max(0, after - before)
    })
    const selectedItems = synced.filter((message) => belongsToSelected(message))
    if (selectedItems.length > 0) {
      mergeCurrentMessages(selectedItems)
      scheduleBurnCountdowns(selectedItems)
      const selectedConversationId = selected.value?.conversationId
      if (selectedConversationId
        && selectedItems.some((message) => message.fromUserId !== currentUser.value?.id)) {
        sendReadPosition(selectedConversationId, selectedItems)
      }
    }
    if (synchronizedUnreadCount > 0) {
      toast.push(
          `连接恢复，已同步 ${synchronizedUnreadCount} 条未读消息`,
          'default',
          3000,
      )
    }

    const denied = Array.isArray(envelope.payload.deniedConversationIds)
      ? envelope.payload.deniedConversationIds.map(String)
      : []
    denied.forEach((conversationId) => {
      inaccessibleConversationIds.add(conversationId)
      recordSummaryDelta({ kind: 'remove', conversationId })
    })
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
      updateConversationPreview(target, belongsToSelected(target), true)
    }
    await cacheMessages([target]).catch(() => undefined)
  }

  function handleReadEvent(envelope: WsEnvelope): void {
    const conversationId = envelope.conversationId
    if (!conversationId) return
    const readerId = Number(envelope.payload.userId)
    const lastReadSequence = Number(envelope.payload.lastReadSequence)
    const currentUserId = currentUser.value?.id
    if (!Number.isSafeInteger(lastReadSequence)
      || !Number.isSafeInteger(readerId)
      || !currentUserId) return
    if (readerId === currentUserId) {
      const lastSequence = Number(envelope.payload.lastSequence)
      const unreadCount = Number(envelope.payload.unreadCount)
      if (!Number.isSafeInteger(lastSequence)
        || lastSequence < 0
        || !Number.isSafeInteger(unreadCount)
        || unreadCount < 0) {
        void refreshConversationSummariesAfterCurrent().catch(() => undefined)
        return
      }
      const readDelta: ConversationReadDelta = {
        conversationId,
        readerId,
        currentUserId,
        lastSequence,
        lastReadSequence,
        unreadCount,
      }
      if (conversationReadRequiresSnapshot(conversationSummaries.value, readDelta)) {
        void refreshConversationSummariesAfterCurrent().catch(() => undefined)
        return
      }
      recordSummaryDelta({ kind: 'read', value: readDelta })
      if (selected.value?.conversationId === conversationId) {
        selected.value = {
          ...selected.value,
          unreadCount: getConversationSummary(conversationId)?.unreadCount || 0,
          lastReadSequence,
        }
      }
      return
    }
    if (conversationId !== selected.value?.conversationId) return
    messages.value.forEach((message) => {
      if (message.fromUserId === currentUser.value?.id
        && message.sequence != null
        && message.sequence <= lastReadSequence) {
        message.deliveryState = 'READ'
        message.status = 1
      }
    })
  }

  function handleConversationRemoved(envelope: WsEnvelope): void {
    const conversationId = envelope.conversationId
    if (!conversationId) return
    forgetConversation(conversationId)
    if (selected.value?.conversationId !== conversationId) return
    selected.value = null
    messages.value = []
    members.value = []
    toast.push('你已不在该会话，未读状态已清除', 'warning')
  }

  function synchronizeAfterReconnect(): Promise<void> {
    if (synchronizationPromise) return synchronizationPromise
    synchronizationPromise = (async () => {
      let hasMore = false
      for (let page = 0; page < 50; page += 1) {
        const positions = await loadPositions().catch(() => ({} as Record<string, number>))
        Object.entries(positions).forEach(([conversationId, sequence]) => {
          if (!Number.isSafeInteger(sequence) || sequence < 0) return
          runtimePositions.set(conversationId, Math.max(
            runtimePositions.get(conversationId) || 0,
            sequence,
          ))
        })
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
      if (hasMore) throw new Error('待同步消息较多，将在下次同步时继续补拉')
      await flushOutbox()
    })().finally(() => { synchronizationPromise = null })
    return synchronizationPromise
  }

  async function refreshAndSynchronize(): Promise<void> {
    await refreshConversationSummaries()
    await synchronizeAfterReconnect()
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
      ...conversationTarget(conversation),
    }
    await queueMessage(conversation, payload)
    return true
  }

  async function sendFile(file: File): Promise<void> {
    const conversation = selected.value
    if (!conversation) return
    if (!ws.connected.value) throw new Error('文件需要连接节点后上传；文本消息仍可离线发送')
    const image = file.type.startsWith('image/')
    let attachment: FileAttachmentData
    if (conversation.kind === 'private'
      && peerFiles.supported.value
      && fileTransferSettings.preferDirectFileTransfer.value) {
      try {
        attachment = await peerFiles.sendDirect(file, conversation.conversationId, conversation.id)
      } catch {
        relayTransferLabel.value = '直传不可用，正在切换节点中转…'
        toast.push('设备直传不可用，已自动切换节点中转', 'warning', 2600)
        attachment = await uploadThroughNode(
          file,
          conversation.conversationId,
          image,
          peerFiles.lastFailedTransferId.value || undefined,
        )
      } finally {
        relayTransferLabel.value = ''
      }
    } else {
      relayTransferLabel.value = conversation.kind === 'private'
        ? '正在通过节点中转…'
        : '群组或房间文件正在通过节点中转…'
      try {
        attachment = await uploadThroughNode(file, conversation.conversationId, image)
      } finally {
        relayTransferLabel.value = ''
      }
    }
    const payload: ChatSendPayload = {
      contentType: image ? 'image' : 'file',
      content: JSON.stringify(attachment),
      isBurn: false,
      ...conversationTarget(conversation),
    }
    await queueMessage(conversation, payload)
  }

  async function uploadThroughNode(
    file: File,
    conversationId: string,
    image: boolean,
    transferId?: string,
  ): Promise<FileAttachmentData> {
    const uploaded = await resumableFiles.upload(file, conversationId)
    if (transferId) {
      ws.sendEvent('FILE_TRANSFER_RELAY_COMPLETE', {
        transferId,
        storedFileName: uploaded.fileName,
      }, { requestId: createRequestId(), conversationId })
    }
    return image
      ? {
          url: uploaded.url,
          thumbnailUrl: uploaded.thumbnailUrl,
          originalUrl: uploaded.url,
          name: uploaded.originalName || file.name,
          size: uploaded.fileSize,
          mime: uploaded.fileType || file.type,
          fileHash: uploaded.fileHash,
          transferId,
          transferPath: 'NODE_RELAY',
        }
      : {
          name: uploaded.originalName || file.name,
          size: uploaded.fileSize,
          url: uploaded.url,
          mime: uploaded.fileType || file.type,
          fileHash: uploaded.fileHash,
          transferId,
          transferPath: 'NODE_RELAY',
        }
  }

  function conversationTarget(conversation: Conversation): Pick<ChatSendPayload, 'toUserId' | 'groupId'> {
    if (conversation.kind === 'private') return { toUserId: conversation.id }
    if (conversation.kind === 'group') return { groupId: conversation.id }
    return {}
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
      updateConversationPreview(target, belongsToSelected(target), true)
      void cacheMessages([target]).catch(() => undefined)
    }
    return true
  }

  function transmitReadPosition(conversationId: string, lastReadSequence: number): boolean {
    if (!ws.connected.value) return false
    return ws.sendEvent('CHAT_READ', { lastReadSequence }, {
      requestId: createRequestId(),
      conversationId,
    })
  }

  function flushPendingReadPositions(): void {
    pendingReadPositions.forEach((lastReadSequence, conversationId) => {
      if (transmitReadPosition(conversationId, lastReadSequence)) {
        pendingReadPositions.delete(conversationId)
      }
    })
  }

  function sendReadPosition(conversationId: string, source: readonly ChatMessage[]): void {
    const lastReadSequence = source.reduce(
      (maximum, message) => Math.max(maximum, message.sequence || 0),
      0,
    )
    if (lastReadSequence <= 0) return
    const sent = transmitReadPosition(conversationId, lastReadSequence)
    if (!sent) {
      // 重连 SYNC 阶段 connected 仍为 false；挂起读位点，转为 ONLINE 后统一补发，
      // 否则服务端和其他设备会残留幻影未读。
      pendingReadPositions.set(
        conversationId,
        Math.max(pendingReadPositions.get(conversationId) || 0, lastReadSequence),
      )
    } else if ((pendingReadPositions.get(conversationId) || 0) <= lastReadSequence) {
      pendingReadPositions.delete(conversationId)
    }
    const currentUserId = currentUser.value?.id
    const current = getConversationSummary(conversationId)
    if (!currentUserId || !current) return
    recordSummaryDelta({
      kind: 'read',
      value: {
        conversationId,
        readerId: currentUserId,
        currentUserId,
        lastSequence: Math.max(current.lastSequence, lastReadSequence),
        lastReadSequence,
        unreadCount: lastReadSequence >= current.lastSequence ? 0 : current.unreadCount,
      },
    })
  }

  async function recordReceivedPositions(source: readonly ChatMessage[]): Promise<void> {
    const candidates = new Map<string, number[]>()
    source.forEach((message) => {
      if (!message.conversationId
        || !Number.isSafeInteger(message.sequence)
        || (message.sequence || 0) <= 0) return
      const sequences = candidates.get(message.conversationId) || []
      sequences.push(message.sequence!)
      candidates.set(message.conversationId, sequences)
    })
    const persisted = await loadPositions().catch(() => ({} as Record<string, number>))
    await Promise.all([...candidates].map(async ([conversationId, sequences]) => {
      const current = Math.max(
        runtimePositions.get(conversationId) || 0,
        persisted[conversationId] || 0,
      )
      runtimePositions.set(conversationId, current)
      const contiguous = advanceContiguousSequence(current, sequences)
      if (contiguous > current) await recordPosition(conversationId, contiguous)
    }))
  }

  async function recordSynchronizedPositions(
    source: readonly ChatMessage[],
    rawLatestPositions: unknown,
  ): Promise<void> {
    const maximums = new Map<string, number>()
    source.forEach((message) => {
      if (!message.conversationId
        || !Number.isSafeInteger(message.sequence)
        || (message.sequence || 0) <= 0) return
      maximums.set(message.conversationId, Math.max(
        maximums.get(message.conversationId) || 0,
        message.sequence!,
      ))
    })
    if (rawLatestPositions
      && typeof rawLatestPositions === 'object'
      && !Array.isArray(rawLatestPositions)) {
      Object.entries(rawLatestPositions as Record<string, unknown>)
        .forEach(([conversationId, rawSequence]) => {
          // A returned page can end before the authoritative latest position.
          // Only use latestPositions when this conversation returned no rows;
          // otherwise the page maximum is the safe cursor for the next request.
          if (!conversationId || maximums.has(conversationId)) return
          const sequence = Number(rawSequence)
          if (!Number.isSafeInteger(sequence) || sequence <= 0) return
          maximums.set(conversationId, sequence)
        })
    }
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

  function updateConversationPreview(
    message: ChatMessage,
    selectedConversation = belongsToSelected(message),
    forcePreview = false,
  ): void {
    const conversationId = message.conversationId
    const currentUserId = currentUser.value?.id
    if (!conversationId || !currentUserId || inaccessibleConversationIds.has(conversationId)) return

    const previewContent = message.status === 2
        ? '消息已焚毁'
        : message.content

    const messageTime = message.createTime || message.timestamp || message.clientCreatedAt || new Date().toISOString()

    recordSummaryDelta({
      kind: 'message',
      value: {
        conversationId,
        sequence: message.sequence,
        fromUserId: message.fromUserId,
        currentUserId,
        content: previewContent,
        contentType: message.status === 2 ? 'text' : message.type,
        createdAt: messageTime,
        selected: selectedConversation,
        forcePreview,
      },
    })

    // 临时房间通过 conversationId 匹配。
    const room = rooms.value.find(
        (item) => item.conversationId === conversationId,
    )

    if (room) {
      return
    }

    // 普通群聊继续同步原有群组目录。
    if (message.groupId) {
      const group = groups.value.find(
          (item) => item.id === message.groupId,
      )

      if (group) {
        group.lastMessage = previewContent
        group.lastMessageType = message.type
        group.lastMessageTime = message.createTime
        persistConversationDirectory()
      }

      return
    }

    // 私聊继续同步好友目录。
    const me = currentUser.value?.id
    const otherId = message.fromUserId === me
        ? message.toUserId
        : message.fromUserId

    const friend = friends.value.find(
        (item) => item.friendId === otherId,
    )

    if (friend) {
      friend.lastMessage = previewContent
      friend.lastMessageType = message.type
      friend.lastMessageTime = message.createTime
      persistConversationDirectory()
    }
  }

  async function persistConversationDirectory(): Promise<void> {
    await saveConversationDirectory(
      friends.value,
      groups.value,
      rooms.value,
      Object.values(conversationSummaries.value),
    ).catch(() => undefined)
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
    rooms: readonly(rooms),
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
    fileTransferLabel,
    conversations,
    visibleConversations,
    totalUnreadCount,
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
    forgetConversation,
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

function temporaryRoomStatusLabel(status: TemporaryRoom['status']): string {
  return {
    ACTIVE: '临时协作房间',
    EXPIRING: '即将到期',
    FROZEN: '已冻结，只读',
    ARCHIVED: '已归档，只读',
    DESTROYED: '已销毁',
  }[status]
}
