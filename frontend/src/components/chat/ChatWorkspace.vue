<script setup lang="ts">
import type { ChatMessage, Conversation, GroupMember, User } from '../../types'
import UiIcon from '../base/UiIcon.vue'
import UserAvatar from '../base/UserAvatar.vue'
import ContextPanel from './ContextPanel.vue'
import MessageComposer from './MessageComposer.vue'
import MessageThread from './MessageThread.vue'

interface Props {
  conversation: Conversation
  messages: readonly ChatMessage[]
  user: User
  members: readonly GroupMember[]
  loadingMessages?: boolean
  typingLabel?: string
  connected: boolean
  uploading?: boolean
  transferLabel?: string
  writable?: boolean
  fileAllowed?: boolean
  statusLabel?: string
  mobile?: boolean
  replyTo?: ChatMessage | null
  contextOpen?: boolean
}

withDefaults(defineProps<Props>(), {
  loadingMessages: false,
  typingLabel: '',
  uploading: false,
  transferLabel: '',
  writable: true,
  fileAllowed: true,
  statusLabel: '',
  mobile: false,
  replyTo: null,
  contextOpen: false,
})

const emit = defineEmits<{
  back: []
  openContext: []
  recall: [messageId: string]
  burn: [messageId: string]
  reply: [message: ChatMessage]
  retry: [clientMsgId: string]
  cancelPending: [clientMsgId: string]
  send: [content: string, burn: boolean]
  typing: []
  file: [file: File]
  cancelReply: []
  closeContext: []
  togglePin: []
  toggleMute: []
  deleteFriend: []
  updateRemark: [remark: string]
  leaveRoom: []
}>()
</script>

<template>
  <section class="workspace chat-workspace apple-content-surface" aria-label="会话工作区">
    <header class="workspace-header">
      <button v-if="mobile" class="back-button" type="button" aria-label="返回会话列表" @click="emit('back')">
        <UiIcon name="back" :size="21" />
      </button>
      <button class="header-profile" type="button" aria-label="查看会话详情" @click="emit('openContext')">
        <UserAvatar :name="conversation.name" :avatar="conversation.avatar" :size="42" />
        <span class="workspace-title">
          <strong>{{ conversation.name }}</strong>
        </span>
      </button>
    </header>

    <MessageThread
      :conversation="conversation"
      :messages="messages"
      :user="user"
      :members="members"
      :loading="loadingMessages"
      :typing-label="typingLabel"
      @recall="emit('recall', $event)"
      @burn="emit('burn', $event)"
      @reply="emit('reply', $event)"
      @retry="emit('retry', $event)"
      @cancel-pending="emit('cancelPending', $event)"
    />
    <MessageComposer
      :conversation="conversation"
      :reply-to="replyTo"
      :connected="connected"
      :uploading="uploading"
      :transfer-label="transferLabel"
      :writable="writable"
      :file-allowed="fileAllowed"
      :status-label="statusLabel"
      @send="(content, burn) => emit('send', content, burn)"
      @typing="emit('typing')"
      @file="emit('file', $event)"
      @cancel-reply="emit('cancelReply')"
    />

    <ContextPanel
      :open="contextOpen"
      :conversation="conversation"
      :members="members"
      @close="emit('closeContext')"
      @toggle-pin="emit('togglePin')"
      @toggle-mute="emit('toggleMute')"
      @delete-friend="emit('deleteFriend')"
      @update-remark="emit('updateRemark', $event)"
      @leave-room="emit('leaveRoom')"
    />
  </section>
</template>

<style scoped>
.chat-workspace {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: minmax(0, auto) minmax(0, 1fr) max-content;
  overflow: hidden;
}

.workspace-header {
  display: flex;
  min-height: 70px;
  padding: 12px 18px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--separator);
  background: var(--surface-glass);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
}

.header-profile {
  display: flex;
  min-width: 0;
  padding: 4px;
  align-items: center;
  gap: 12px;
  border: 0;
  border-radius: 12px;
  color: inherit;
  text-align: left;
  background: none;
  cursor: pointer;
  transition: background-color 150ms ease;
}
.header-profile:hover { background: var(--hover); }
.header-profile:focus-visible,
.back-button:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 58%, transparent); outline-offset: 2px; }
.workspace-title { display: grid; min-width: 0; flex: 1; }
.workspace-title strong { overflow: hidden; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.back-button {
  display: none;
  width: 40px;
  height: 40px;
  padding: 0;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--glass-border);
  border-radius: 13px;
  color: var(--ink-faint);
  background: var(--surface-glass);
  cursor: pointer;
}

:deep(.message-thread) { min-height: 0; overflow-y: auto; }
:deep(.composer-wrap) { min-height: 0; }

@media (max-width: 760px) {
  .workspace-header {
    min-height: 64px;
    padding: max(10px, env(safe-area-inset-top)) max(13px, env(safe-area-inset-right)) 10px max(13px, env(safe-area-inset-left));
  }
  .back-button { display: grid; }
}

@media (prefers-reduced-motion: reduce) {
  .header-profile { transition: none; }
}
</style>
