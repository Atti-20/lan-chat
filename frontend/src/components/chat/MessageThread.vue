<script setup lang="ts">
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import type { ChatMessage, Conversation, GroupMember, User } from '../../types'
import { formatMessageTime } from '../../utils/format'
import UserAvatar from '../base/UserAvatar.vue'
import AttachmentBubble from './AttachmentBubble.vue'

interface Props {
  conversation: Conversation
  messages: readonly ChatMessage[]
  user: User
  members: readonly GroupMember[]
  loading?: boolean
  typingLabel?: string
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  typingLabel: '',
})
const emit = defineEmits<{
  recall: [messageId: string]
  burn: [messageId: string]
  reply: [message: ChatMessage]
}>()
const threadRef = useTemplateRef<HTMLDivElement>('thread')
const memberMap = computed(() => new Map(props.members.map((member) => [member.userId, member])))

watch(
  () => [props.messages.length, props.conversation.id] as const,
  async () => {
    await nextTick()
    threadRef.value?.scrollTo({ top: threadRef.value.scrollHeight, behavior: 'smooth' })
  },
  { immediate: true },
)

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
  if (!isSelf(message) || !message.createTime || message.isRecalled === 1 || message.status === 2) return false
  return Date.now() - new Date(message.createTime).getTime() <= 120_000
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

    <ol v-else class="message-list">
      <li
        v-for="message in messages"
        :key="message.messageId"
        class="message-row"
        :class="{ 'message-row--self': isSelf(message) }"
      >
        <div class="message-stack">
          <span v-if="conversation.kind === 'group' && !isSelf(message)" class="sender-name">{{ senderName(message) }}</span>
          <div
            class="message-bubble"
            :class="[
              `message-bubble--${isSelf(message) ? 'self' : 'peer'}`,
              { 'message-bubble--attachment': ['image', 'file'].includes(messageType(message)) },
            ]"
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
              />
              <p v-else class="message-text">{{ message.content }}</p>
              <span v-if="Number(message.isBurn) === 1" class="burn-label">阅后即焚</span>
            </template>
          </div>
          <div class="message-meta">
            <time>{{ formatMessageTime(message.createTime) }}</time>
            <span v-if="isSelf(message) && message.status === 1">已读</span>
            <div v-if="message.isRecalled !== 1 && message.status !== 2" class="message-actions">
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
  </div>
</template>

<style scoped>
.message-thread { position: relative; min-height: 0; padding: 24px clamp(18px, 3vw, 42px); overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: rgba(92,124,156,.2) transparent; }
.message-list { display: grid; max-width: 880px; padding: 0; margin: 0 auto; gap: 14px; list-style: none; }
.message-row { display: flex; align-items: flex-end; gap: 4px; }
.message-row--self { flex-direction: row-reverse; }
.message-stack { display: grid; max-width: min(72%, 620px); gap: 4px; }
.message-row--self .message-stack { justify-items: end; }
.sender-name { padding-left: 7px; color: #657a90; font-size: 10px; font-weight: 650; }
.message-bubble { position: relative; min-width: 50px; padding: 10px 13px; border: 1px solid rgba(255,255,255,.7); border-radius: 19px 19px 19px 7px; background: rgba(255,255,255,.66); box-shadow: inset 0 1px 0 rgba(255,255,255,.95), 0 7px 16px rgba(45,78,113,.08); }
.message-bubble--self { border-color: rgba(255,255,255,.26); border-radius: 19px 19px 7px 19px; color: white; background: linear-gradient(145deg, #168dff, #0879ee 68%, #5b62ea); box-shadow: inset 0 1px 0 rgba(255,255,255,.35), 0 9px 20px rgba(10,132,255,.2); }
.message-bubble--attachment { padding: 3px; overflow: hidden; }
.message-text { margin: 0; font-size: 14px; line-height: 1.55; overflow-wrap: anywhere; white-space: pre-wrap; }
.message-placeholder { color: inherit; font-size: 12px; font-style: italic; opacity: .68; }
.reply-quote { display: grid; padding: 7px 9px; margin-bottom: 7px; gap: 2px; border-left: 3px solid currentColor; border-radius: 7px; font-size: 10px; background: rgba(16,35,63,.08); opacity: .78; }
.message-bubble--self .reply-quote { background: rgba(255,255,255,.14); }
.reply-quote span { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.burn-label { display: inline-block; margin-top: 6px; padding: 3px 6px; border: 1px solid currentColor; border-radius: 7px; font-size: 8px; font-weight: 750; opacity: .68; }
.message-meta { display: flex; min-height: 15px; padding: 0 5px; align-items: center; gap: 6px; color: #8a9bad; font-size: 9px; }
.message-actions { display: flex; gap: 3px; opacity: 0; transition: opacity 150ms ease; }
.message-row:hover .message-actions,
.message-actions:focus-within { opacity: 1; }
.message-actions button { padding: 1px 5px; border: 0; color: #58728b; font-size: 9px; background: transparent; cursor: pointer; }
.thread-state { display: grid; height: 100%; min-height: 320px; place-items: center; align-content: center; color: var(--ink-soft); text-align: center; }
.thread-state p { margin: 8px 0 0; font-size: 12px; }
.thread-spinner { width: 26px; height: 26px; border: 2px solid rgba(10,132,255,.16); border-top-color: var(--blue); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.empty-wave { display: grid; width: 66px; height: 66px; margin-bottom: 17px; place-items: center; border: 1px solid rgba(255,255,255,.75); border-radius: 24px 24px 24px 11px; color: var(--blue); font-size: 28px; background: rgba(255,255,255,.44); box-shadow: inset 0 1px 0 #fff, 0 12px 28px rgba(42,81,121,.1); }
.typing-pill { position: sticky; bottom: 4px; display: flex; width: max-content; padding: 8px 12px; margin: 10px auto 0; align-items: center; gap: 4px; border: 1px solid rgba(255,255,255,.76); border-radius: 999px; color: var(--ink-soft); font-size: 10px; background: rgba(255,255,255,.7); box-shadow: 0 8px 20px rgba(46,78,111,.1); backdrop-filter: blur(18px); }
.typing-pill span { width: 4px; height: 4px; border-radius: 50%; background: var(--blue); animation: bounce 1s infinite alternate; }
.typing-pill span:nth-child(2) { animation-delay: .16s; }.typing-pill span:nth-child(3) { animation-delay: .32s; margin-right: 4px; }
@keyframes bounce { to { transform: translateY(-3px); opacity: .5; } }

@media (max-width: 760px) {
  .message-thread { padding: 18px 12px; }
  .message-stack { max-width: 82%; }
  .message-actions { opacity: 1; }
}

.message-thread { padding: 22px clamp(18px, 3vw, 38px); background: var(--surface); }
.message-list { max-width: 820px; gap: 12px; }
.message-row { gap: 4px; }
.message-stack { gap: 3px; }
.sender-name { color: var(--ink-faint); font-weight: 500; }
.message-bubble {
  padding: 9px 13px;
  border: 0;
  border-radius: 18px 18px 18px 6px;
  background: var(--fill);
  box-shadow: none;
}
.message-bubble--self {
  border-radius: 18px 18px 6px 18px;
  background: var(--blue);
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
</style>
