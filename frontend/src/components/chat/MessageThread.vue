<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, shallowRef, useTemplateRef, watch } from 'vue'
import type { ChatMessage, Conversation, GroupMember, MentionReadReceipt, User } from '../../types'
import { formatMessageTime } from '../../utils/format'
import { api } from '../../services/api'
import UserAvatar from '../base/UserAvatar.vue'
import AttachmentBubble from './AttachmentBubble.vue'
import BroadcastNoticeBubble from './BroadcastNoticeBubble.vue'
import MentionReadReceiptSheet from './MentionReadReceiptSheet.vue'

interface Props {
  conversation: Conversation
  messages: readonly ChatMessage[]
  user: User
  members: readonly GroupMember[]
  loading?: boolean
  typingLabel?: string
  mentionReceiptRefreshRevision?: number
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  typingLabel: '',
  mentionReceiptRefreshRevision: 0,
})
const emit = defineEmits<{
  recall: [messageId: string]
  burn: [messageId: string]
  reply: [message: ChatMessage]
  retry: [clientMsgId: string]
  cancelPending: [clientMsgId: string]
}>()
const threadRef = useTemplateRef<HTMLDivElement>('thread')
const messageListRef = useTemplateRef<HTMLOListElement>('messageList')
const memberMap = computed(() => new Map(props.members.map((member) => [member.userId, member])))
const senderCanSeeMentionReadState = computed(() => props.conversation.kind === 'group'
  && props.members.some((member) => member.userId === props.user.id && member.role >= 0))
const mentionReceiptMessage = shallowRef<ChatMessage | null>(null)
const mentionReceipt = shallowRef<MentionReadReceipt | null>(null)
const mentionReceiptLoading = shallowRef(false)
const mentionReceiptError = shallowRef('')
const mentionReceiptCache = shallowRef<Map<string, MentionReadReceipt>>(new Map())
const mentionReceiptRequests = new Map<string, {
  version: number
  promise: Promise<MentionReadReceipt>
}>()
const mentionReceiptFetchVersions = new Map<string, number>()
let mentionPressTimer: number | null = null
let mentionReceiptRequestId = 0
let scrollFrame: number | null = null
let scrollRequestId = 0
let layoutObserver: ResizeObserver | null = null
let layoutSettleTimer: number | null = null
let keepBottomPinnedUntil = 0

class MentionReceiptRequestSupersededError extends Error {
  constructor() {
    super('已读状态请求已过期')
  }
}

watch(
  () => [
    props.conversation.conversationId,
    props.conversation.kind,
    props.messages,
    props.loading,
  ] as const,
  ([conversationId, conversationKind, messages, loading], previous) => {
    const conversationChanged = !previous
      || previous[0] !== conversationId
      || previous[1] !== conversationKind
    const loadingFinished = previous?.[3] === true && !loading
    const messagesChanged = previous?.[2] !== messages

    if (conversationChanged || loadingFinished) {
      // The history response can replace cached messages without changing the
      // message count. Scroll after the final layout, without an animation.
      // Attachments resolve their image URL after the history request, so keep
      // the initial position pinned while the message list is still resizing.
      if (!loading) {
        keepBottomPinnedUntil = Date.now() + 5_000
      }
      scheduleScrollToBottom('auto', !loading)
      return
    }
    if (messagesChanged && !loading) {
      if (isNearBottom() || Date.now() <= keepBottomPinnedUntil) {
        scheduleScrollToBottom('smooth')
      }
    }
  },
  { immediate: true, flush: 'post' },
)

onBeforeUnmount(() => {
  mentionReceiptRequestId += 1
  scrollRequestId += 1
  if (scrollFrame !== null) cancelAnimationFrame(scrollFrame)
  stopLayoutSettling()
  if (mentionPressTimer !== null) window.clearTimeout(mentionPressTimer)
})

function isNearBottom(tolerance = 160): boolean {
  const thread = threadRef.value
  if (!thread) return true
  const remaining = thread.scrollHeight - thread.scrollTop - thread.clientHeight
  return remaining <= tolerance
}

function handleAttachmentLayoutChange(): void {
  const initialLayoutStillSettling = Date.now() <= keepBottomPinnedUntil
  if (!initialLayoutStillSettling && !isNearBottom()) return
  scheduleScrollToBottom(
      'auto',
      initialLayoutStillSettling
  )
}

function scheduleScrollToBottom(behavior: ScrollBehavior, settleLayout = false): void {
  const requestId = ++scrollRequestId
  if (scrollFrame !== null) cancelAnimationFrame(scrollFrame)
  stopLayoutSettling()

  void nextTick(() => {
    if (requestId !== scrollRequestId) return
    scrollFrame = requestAnimationFrame(() => {
      scrollFrame = null
      if (requestId !== scrollRequestId) return
      scrollToBottom(behavior)
      if (settleLayout) startLayoutSettling(requestId)
    })
  })
}

function scrollToBottom(behavior: ScrollBehavior): void {
  const thread = threadRef.value
  if (!thread) return
  const top = Math.max(0, thread.scrollHeight - thread.clientHeight)
  if (behavior === 'auto') {
    thread.scrollTop = top
  } else {
    thread.scrollTo({ top, behavior })
  }
}

function startLayoutSettling(requestId: number): void {
  const list = messageListRef.value
  const finish = () => {
    if (requestId !== scrollRequestId) return
    stopLayoutSettling()
  }
  const resetTimer = () => {
    if (layoutSettleTimer !== null) window.clearTimeout(layoutSettleTimer)
    layoutSettleTimer = window.setTimeout(finish, 1_200)
  }

  if (typeof ResizeObserver !== 'undefined' && list) {
    layoutObserver = new ResizeObserver(() => {
      if (requestId !== scrollRequestId) return
      scrollToBottom('auto')
      resetTimer()
    })
    layoutObserver.observe(list)
  }
  resetTimer()
}

function stopLayoutSettling(): void {
  layoutObserver?.disconnect()
  layoutObserver = null
  if (layoutSettleTimer !== null) window.clearTimeout(layoutSettleTimer)
  layoutSettleTimer = null
}

function isSelf(message: ChatMessage): boolean {
  return message.fromUserId === props.user.id
}

function senderName(message: ChatMessage): string {
  if (isSelf(message)) return props.user.nickname
  if (props.conversation.kind === 'private') return props.conversation.name
  return memberMap.value.get(message.fromUserId)?.nickname || message.fromNickname || `用户 ${message.fromUserId}`
}

function senderAvatar(message: ChatMessage): string | undefined {
  if (isSelf(message)) return props.user.avatar
  if (props.conversation.kind === 'private') return props.conversation.avatar
  return memberMap.value.get(message.fromUserId)?.avatar || message.fromAvatar
}

function messageType(message: ChatMessage): string {
  const t = message.type
  return (t && t !== 'chat') ? t : (message.contentType || 'text')
}

function canRecall(message: ChatMessage): boolean {
  if (!isSelf(message)
    || !message.createTime
    || message.messageId.startsWith('local:')
    || message.deliveryState === 'FAILED'
    || message.isRecalled === 1
    || message.status === 2) return false
  return Date.now() - new Date(message.createTime).getTime() <= 120_000
}

function deliveryLabel(message: ChatMessage): string {
  return ({
    WAITING_NETWORK: '等待连接',
    SENDING: '发送中',
    SENT: '已发送',
    DELIVERED: '已送达',
    READ: '已读',
    FAILED: '发送失败',
  } as const)[message.deliveryState || 'SENT']
}

function hasMentionRecipients(message: ChatMessage): boolean {
  const targets = message.mentionUserIds?.trim()
  // The client emits ALL for @所有人.  The server freezes that into numeric
  // member IDs before delivery, but an acknowledged optimistic message can be
  // rendered in the short interval before its CHAT_DELIVER replacement arrives.
  return targets?.toUpperCase() === 'ALL'
    || Boolean(targets?.split(',').some((id) => /^\d+$/.test(id.trim())))
}

function canShowMentionReadState(message: ChatMessage): boolean {
  return isSelf(message)
    && senderCanSeeMentionReadState.value
    && hasMentionRecipients(message)
    && !message.messageId.startsWith('local:')
}

function mentionReadReceiptFor(message: ChatMessage): MentionReadReceipt | undefined {
  return mentionReceiptCache.value.get(message.messageId)
}

function mentionReadLabel(message: ChatMessage): string {
  const receipt = mentionReadReceiptFor(message)
  return receipt ? `已读 ${receipt.readCount} / 应读 ${receipt.expectedCount}` : ''
}

function cacheMentionReceipt(receipt: MentionReadReceipt): void {
  const next = new Map(mentionReceiptCache.value)
  next.set(receipt.messageId, receipt)
  mentionReceiptCache.value = next
}

function requestMentionReceipt(message: ChatMessage, force = false): Promise<MentionReadReceipt> {
  const cached = mentionReadReceiptFor(message)
  if (cached && !force) return Promise.resolve(cached)

  const pending = mentionReceiptRequests.get(message.messageId)
  if (pending && !force) return pending.promise
  const version = (mentionReceiptFetchVersions.get(message.messageId) || 0) + 1
  mentionReceiptFetchVersions.set(message.messageId, version)

  const request = api.chat.mentionReceipts(message.messageId)
    .then((result) => {
      if (result.messageId !== message.messageId) {
        throw new Error('接收详情与当前消息不匹配')
      }
      // A receipt update can arrive while a previous request is in flight.
      // Only the newest fetch is allowed to touch the cache or the detail
      // sheet; otherwise an old response could restore a stale read count.
      if (mentionReceiptFetchVersions.get(message.messageId) !== version) {
        throw new MentionReceiptRequestSupersededError()
      }
      cacheMentionReceipt(result)
      return result
    })
    .finally(() => {
      if (mentionReceiptRequests.get(message.messageId)?.version === version) {
        mentionReceiptRequests.delete(message.messageId)
      }
    })
  mentionReceiptRequests.set(message.messageId, { version, promise: request })
  return request
}

async function openMentionReceipt(message: ChatMessage): Promise<void> {
  if (!canShowMentionReadState(message)) return
  const requestId = ++mentionReceiptRequestId
  mentionReceiptMessage.value = message
  mentionReceipt.value = mentionReadReceiptFor(message) || null
  mentionReceiptError.value = ''
  mentionReceiptLoading.value = true
  try {
    const result = await requestMentionReceipt(message, true)
    if (requestId !== mentionReceiptRequestId
      || mentionReceiptMessage.value?.messageId !== message.messageId
      || result.messageId !== message.messageId) return
    mentionReceipt.value = result
  } catch (cause) {
    if (cause instanceof MentionReceiptRequestSupersededError) return
    if (requestId !== mentionReceiptRequestId
      || mentionReceiptMessage.value?.messageId !== message.messageId) return
    mentionReceiptError.value = cause instanceof Error ? cause.message : '无法加载@成员已读状态'
  } finally {
    if (requestId === mentionReceiptRequestId
      && mentionReceiptMessage.value?.messageId === message.messageId) {
      mentionReceiptLoading.value = false
    }
  }
}

function closeMentionReceipt(): void {
  mentionReceiptRequestId += 1
  mentionReceiptMessage.value = null
  mentionReceipt.value = null
  mentionReceiptLoading.value = false
  mentionReceiptError.value = ''
}

const mentionReceiptMessages = computed(() => props.messages.filter(canShowMentionReadState))

watch(
  [
    () => props.conversation.conversationId,
    mentionReceiptMessages,
    () => props.mentionReceiptRefreshRevision,
  ],
  ([conversationId, messages, refreshRevision], previous) => {
    const conversationChanged = Boolean(previous && previous[0] !== conversationId)
    const refreshRequested = Boolean(previous
      && previous[2] !== refreshRevision
      && !conversationChanged)
    if (conversationChanged) {
      mentionReceiptCache.value = new Map()
    }
    messages.forEach((message) => {
      void requestMentionReceipt(message, refreshRequested)
        .then((receipt) => {
          if (mentionReceiptMessage.value?.messageId === receipt.messageId) {
            mentionReceipt.value = receipt
          }
        })
        .catch(() => undefined)
    })
  },
  { immediate: true },
)

watch(senderCanSeeMentionReadState, (canSeeMentionReadState) => {
  if (canSeeMentionReadState) return
  mentionReceiptCache.value = new Map()
  closeMentionReceipt()
})

function beginMentionLongPress(message: ChatMessage): void {
  if (!canShowMentionReadState(message)) return
  if (mentionPressTimer !== null) window.clearTimeout(mentionPressTimer)
  mentionPressTimer = window.setTimeout(() => {
    mentionPressTimer = null
    void openMentionReceipt(message)
  }, 520)
}

function cancelMentionLongPress(): void {
  if (mentionPressTimer === null) return
  window.clearTimeout(mentionPressTimer)
  mentionPressTimer = null
}

function handleMentionContext(event: MouseEvent, message: ChatMessage): void {
  if (!canShowMentionReadState(message)) return
  event.preventDefault()
  void openMentionReceipt(message)
}

function canManagePending(message: ChatMessage): boolean {
  return isSelf(message)
    && Boolean(message.clientMsgId)
    && ['WAITING_NETWORK', 'FAILED'].includes(message.deliveryState || '')
}

function repliedMessage(message: ChatMessage): ChatMessage | undefined {
  return message.replyToId ? props.messages.find((item) => item.messageId === message.replyToId) : undefined
}
</script>

<template>
  <div ref="thread" class="message-thread" aria-live="polite">
    <div v-if="loading" class="thread-state">
      <span class="thread-spinner" />
      <p>正在载入对话…</p>
    </div>

    <div v-else-if="messages.length === 0" class="thread-state empty-thread">
      <span class="empty-wave" aria-hidden="true">〰</span>
      <strong>从一句问候开始</strong>
      <p>{{ conversation.kind === 'group' ? '发给所有群成员的第一条消息。' : `你和 ${conversation.name} 还没有聊天记录。` }}</p>
    </div>

    <ol v-else ref="messageList" class="message-list">
      <li
        v-for="message in messages"
        :key="message.messageId"
        class="message-row"
        :class="{ 'message-row--self': isSelf(message) }"
      >
        <UserAvatar
          class="message-avatar"
          :name="senderName(message)"
          :avatar="senderAvatar(message)"
          :size="34"
        />
        <div class="message-stack">
          <span v-if="conversation.kind === 'group' && !isSelf(message)" class="sender-name">{{ senderName(message) }}</span>
          <div
            class="message-bubble"
            :class="[
              `message-bubble--${isSelf(message) ? 'self' : 'peer'}`,
              {
                'message-bubble--attachment': ['image', 'file'].includes(messageType(message)),
                'message-bubble--mention-receipt': canShowMentionReadState(message),
              },
            ]"
            @pointerdown="beginMentionLongPress(message)"
            @pointerup="cancelMentionLongPress"
            @pointercancel="cancelMentionLongPress"
            @pointerleave="cancelMentionLongPress"
            @selectstart.prevent
            @contextmenu="handleMentionContext($event, message)"
          >
            <span v-if="message.isRecalled === 1" class="message-placeholder">这条消息已撤回</span>
            <span v-else-if="message.status === 2" class="message-placeholder">这条消息已焚毁</span>
            <template v-else>
              <div v-if="repliedMessage(message)" class="reply-quote">
                <strong>{{ senderName(repliedMessage(message)!) }}</strong>
                <span>{{ repliedMessage(message)?.content || '附件消息' }}</span>
              </div>
              <AttachmentBubble
                v-if="messageType(message) === 'image' || messageType(message) === 'file'"
                :type="messageType(message) as 'image' | 'file'"
                :content="message.content"
                :outgoing="isSelf(message)"
                @layout-change="handleAttachmentLayoutChange"
              />
              <BroadcastNoticeBubble
                v-else-if="messageType(message) === 'broadcast'"
                :content="message.content"
              />
              <p v-else class="message-text">{{ message.content }}</p>
              <span v-if="Number(message.isBurn) === 1" class="burn-label">阅后即焚</span>
            </template>
          </div>
          <div class="message-meta">
            <time>{{ formatMessageTime(message.createTime) }}</time>
            <span
              v-if="isSelf(message) && ['WAITING_NETWORK', 'SENDING', 'FAILED'].includes(message.deliveryState || '')"
              :class="{ 'delivery-failed': message.deliveryState === 'FAILED' }"
              :title="message.errorMessage"
            >{{ deliveryLabel(message) }}</span>
            <button
              v-if="canShowMentionReadState(message) && mentionReadReceiptFor(message)"
              class="mention-read-status"
              type="button"
              @click="void openMentionReceipt(message)"
            >{{ mentionReadLabel(message) }}</button>
            <div v-if="message.isRecalled !== 1 && message.status !== 2" class="message-actions">
              <button
                v-if="canManagePending(message) && message.deliveryState === 'FAILED'"
                type="button"
                @click="emit('retry', message.clientMsgId!)"
              >重试</button>
              <button
                v-if="canManagePending(message)"
                type="button"
                @click="emit('cancelPending', message.clientMsgId!)"
              >取消</button>
              <button type="button" @click="emit('reply', message)">回复</button>
              <button v-if="canRecall(message)" type="button" @click="emit('recall', message.messageId)">撤回</button>
              <button v-if="!isSelf(message) && Number(message.isBurn) === 1" type="button" @click="emit('burn', message.messageId)">焚毁</button>
            </div>
          </div>
        </div>
      </li>
    </ol>

    <div v-if="typingLabel" class="typing-pill">
      <span /><span /><span />
      {{ typingLabel }}
    </div>

    <MentionReadReceiptSheet
      :open="Boolean(mentionReceiptMessage)"
      :message="mentionReceiptMessage"
      :receipt="mentionReceipt"
      :loading="mentionReceiptLoading"
      :error="mentionReceiptError"
      @close="closeMentionReceipt"
    />
  </div>
</template>

<style scoped>
.message-thread { position: relative; min-width: 0; min-height: 0; overflow-y: auto; overflow-anchor: none; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: rgba(92,124,156,.2) transparent; }
.message-list { display: grid; width: 100%; max-width: none; min-height: 100%; padding: 0; margin: 0; align-content: start; list-style: none; }
.message-row { display: flex; align-items: flex-end; }
.message-row--self { flex-direction: row-reverse; }
.message-avatar { margin-bottom: 18px; }
.message-stack { display: grid; max-width: min(72%, 620px); }
.message-row--self .message-stack { justify-items: end; }
.sender-name { padding-left: 7px; font-size: var(--font-caption); }
.message-bubble { position: relative; min-width: 50px; border: 1px solid rgba(255,255,255,.7); }
.message-bubble--self { border-color: rgba(255,255,255,.26); color: white; }
.message-bubble--attachment { overflow: hidden; }
.message-bubble--mention-receipt { -webkit-touch-callout: none; -webkit-user-select: none; user-select: none; }
.message-text { margin: 0; font-size: var(--font-body); line-height: 1.55; overflow-wrap: anywhere; white-space: pre-wrap; }
.message-placeholder { color: inherit; font-size: var(--font-caption); font-style: italic; opacity: .68; }
.reply-quote { display: grid; padding: 7px 9px; margin-bottom: 7px; gap: 2px; border-left: 3px solid currentColor; border-radius: 7px; font-size: var(--font-caption); background: rgba(16,35,63,.08); opacity: .78; }
.message-bubble--self .reply-quote { background: rgba(255,255,255,.14); }
.reply-quote span { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.burn-label { display: inline-block; margin-top: 6px; padding: 3px 6px; border: 1px solid currentColor; border-radius: 7px; font-size: var(--font-micro); font-weight: 750; opacity: .68; }
.message-meta { display: flex; min-height: 15px; padding: 0 5px; align-items: center; gap: 6px; font-size: var(--font-micro); }
.delivery-failed { color: var(--danger); font-weight: 650; }
.mention-read-status { min-height: 24px; padding: 0 3px; border: 0; color: var(--accent-text); font: inherit; font-weight: 700; background: transparent; cursor: pointer; }
.message-row--self .mention-read-status { color: color-mix(in srgb, var(--accent-text) 85%, var(--ink-soft)); }
.message-actions { display: flex; gap: 3px; opacity: 0; transition: opacity 150ms ease; }
.message-row:hover .message-actions,
.message-actions:focus-within { opacity: 1; }
.message-row--self .message-actions { opacity: 1; }
.message-actions button { padding: 1px 5px; border: 0; font-size: var(--font-micro); background: transparent; cursor: pointer; }
.thread-state { display: grid; height: 100%; min-height: 320px; place-items: center; align-content: center; color: var(--ink-soft); text-align: center; }
.thread-state p { margin: 8px 0 0; font-size: var(--font-caption); }
.thread-spinner { width: 26px; height: 26px; border: 2px solid rgba(10,132,255,.16); border-top-color: var(--accent-text); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.empty-wave { display: grid; width: 66px; height: 66px; margin-bottom: 17px; place-items: center; border: 1px solid rgba(255,255,255,.75); color: var(--accent-text); font-size: 28px; }
.typing-pill { position: sticky; bottom: 4px; display: flex; width: max-content; padding: 8px 12px; margin: 10px auto 0; align-items: center; gap: var(--space-1); border: 1px solid rgba(255,255,255,.76); border-radius: var(--radius-pill); font-size: var(--font-caption); backdrop-filter: blur(18px); }
.typing-pill span { width: 4px; height: 4px; border-radius: 50%; background: var(--blue); animation: bounce 1s infinite alternate; }
.typing-pill span:nth-child(2) { animation-delay: .16s; }.typing-pill span:nth-child(3) { animation-delay: .32s; margin-right: 4px; }
@keyframes bounce { to { transform: translateY(-3px); opacity: .5; } }

@media (max-width: 760px) {
  .message-stack { max-width: 82%; }
  .message-actions { opacity: 1; }
}

.message-thread { padding: 22px clamp(18px, 3vw, 38px); background: var(--surface); }
.message-list { width: 100%; max-width: none; gap: var(--space-3); }
.message-row { gap: 10px; }
.message-avatar { margin-bottom: 17px; }
.message-stack { gap: 3px; }
.sender-name { color: var(--ink-faint); font-weight: 500; }
.message-bubble {
  padding: 9px 13px;
  border: 0;
  border-radius: var(--radius-bubble) var(--radius-bubble) var(--radius-bubble) 6px;
  background: var(--fill);
  box-shadow: none;
}
.message-bubble--self {
  border-radius: var(--radius-bubble) var(--radius-bubble) 6px var(--radius-bubble);
  background: var(--action-bg);
  box-shadow: none;
}
.message-bubble--attachment { padding: 3px; }
.message-meta { color: var(--ink-faint); }
.message-actions button { color: var(--ink-soft); }
.empty-wave {
  width: 58px;
  height: 58px;
  border: 0;
  border-radius: 50%;
  background: var(--fill);
  box-shadow: none;
}
.typing-pill {
  border-color: var(--glass-border);
  color: var(--ink-soft);
  background: var(--surface-glass);
  box-shadow: 0 4px 16px var(--shadow-color);
  backdrop-filter: blur(16px) saturate(150%);
}

@media (max-width: 760px) {
  .message-thread {
    padding: 14px max(10px, env(safe-area-inset-right)) 18px max(10px, env(safe-area-inset-left));
  }
  .message-list { gap: 10px; }
  .message-stack { max-width: 82%; }
  .message-actions { opacity: 1; }
}
</style>
