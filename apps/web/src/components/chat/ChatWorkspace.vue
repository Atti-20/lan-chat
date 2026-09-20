<script setup lang="ts">
import { shallowRef } from 'vue'
import { useGlassChromeMetrics } from '../../composables/useGlassChromeMetrics'
import type { ChatMessage, RecoveryTerminal, Conversation, GroupMember, User } from '../../types'
import UiIcon from '../base/UiIcon.vue'
import UserAvatar from '../base/UserAvatar.vue'
import ContextPanel from './ContextPanel.vue'
import MessageComposer from './MessageComposer.vue'
import MessageThread from './MessageThread.vue'

const workspaceElement = shallowRef<HTMLElement | null>(null)
useGlassChromeMetrics(workspaceElement, 'workspace')

interface Props {
  conversation: Conversation
  messages: readonly ChatMessage[]
  user: User
  members: readonly GroupMember[]
  loadingMessages?: boolean
  readScope?: string
  recoveryStatus?: string
  recoveryTerminals?: readonly RecoveryTerminal[]
  typingLabel?: string
  mentionReceiptRefreshRevision?: number
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
  readScope: '',
  recoveryStatus: '',
  recoveryTerminals: () => [],
  typingLabel: '',
  mentionReceiptRefreshRevision: 0,
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
  readVisibility: [scope: string, sequences: readonly number[]]
  back: []
  openContext: []
  recall: [messageId: string]
  burn: [messageId: string]
  reply: [message: ChatMessage]
  retry: [clientMsgId: string]
  cancelPending: [clientMsgId: string]
  send: [content: string, burn: boolean, mentionUserIds?: string]
  composerSubmitted: []
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
  <section ref="workspaceElement" class="workspace chat-workspace apple-content-surface" aria-label="会话工作区">
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
      :recovery-status="recoveryStatus"
      :recovery-terminals="recoveryTerminals"
      :read-scope="readScope"
      @read-visibility="(scope,sequences)=>emit('readVisibility',scope,sequences)"
      :typing-label="typingLabel"
      :mention-receipt-refresh-revision="mentionReceiptRefreshRevision"
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
      :members="members"
      :current-user-id="user.id"
      @send="(content, burn, mentionUserIds) => emit('send', content, burn, mentionUserIds)"
      @composer-submitted="emit('composerSubmitted')"
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
/* One scroll layer behind a separate navigation/control layer. */
.chat-workspace.workspace {
  position: relative; display: grid; min-width: 0; min-height: 0;
  grid-template-rows: minmax(0, 1fr); overflow: hidden;
}
.workspace-header {
  position: absolute; z-index: 3; top: 0; left: 0; right: 0;
  display: flex; min-height: 70px; padding: 10px 16px;
  align-items: center; gap: var(--space-3); pointer-events: none;
  border: 0; background: none;
}
.header-profile, .back-button {
  pointer-events: auto; display: flex; min-width: 0; min-height: 48px;
  align-items: center; gap: 10px; padding: 4px 12px 4px 4px;
  border: 1px solid var(--mx-color-glass-rim); border-radius: 28px;
  color: var(--ink); text-align: left; cursor: pointer;
  background: var(--mx-color-glass-readable);
  box-shadow: 0 3px 12px var(--shadow-color), inset 0 1px 0 var(--highlight);
  -webkit-backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(150%);
  backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(150%);
}
.header-profile { max-width: calc(100% - 60px); }
.header-profile:hover { border-color: var(--accent-text); }
.header-profile:focus-visible, .back-button:focus-visible { outline: 2px solid var(--accent-text); outline-offset: 2px; }
.workspace-title { display: grid; min-width: 0; flex: 1; }
.workspace-title strong { overflow: hidden; font-size: var(--font-body-lg); text-overflow: ellipsis; white-space: nowrap; }
.back-button { display: none; width: 48px; padding: 0; justify-content: center; flex: 0 0 auto; }
.chat-workspace :deep(.message-thread) {
  grid-area: 1 / 1; min-height: 0; height: 100%; overflow-y: auto;
  padding-top: calc(var(--mx-runtime-chrome-top, 70px) + 12px);
  padding-bottom: calc(var(--mx-runtime-chrome-bottom, 100px) + 12px);
  scroll-padding-top: calc(var(--mx-runtime-chrome-top, 70px) + 12px);
  scroll-padding-bottom: calc(var(--mx-runtime-chrome-bottom, 100px) + 12px);
}
.chat-workspace :deep(.composer-wrap) {
  position: absolute; z-index: 3; bottom: 0; left: 0; right: 0;
  min-height: 0; overflow: visible;
  padding: 8px max(12px, env(safe-area-inset-right)) max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  border: 0; background: transparent;
}
.chat-workspace :deep(.composer) {
  border: 1px solid var(--mx-color-glass-rim);
  border-radius: 26px; padding: 8px; gap: 8px;
  background: var(--mx-color-glass-readable);
  box-shadow: 0 8px 26px var(--shadow-color), inset 0 1px 0 var(--highlight);
  -webkit-backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(145%);
  backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(145%);
}
.chat-workspace :deep(.composer textarea) { color: var(--ink); min-height: 48px; padding-block: 12px; }
.chat-workspace :deep(.composer textarea::placeholder) { color: var(--ink-soft); }
.chat-workspace :deep(.composer-hint) { display: block; margin: 6px 8px 0; padding: 3px 8px; border-radius: 10px; background: var(--surface); color: var(--ink-soft); font-size: .75rem; }
.chat-workspace :deep(.tool-button), .chat-workspace :deep(.send-button) { width: 48px; height: 48px; min-width: 48px; }
.chat-workspace :deep(.send-button:hover) { transform: none; }
@media (max-width: 760px) {
  .workspace-header { padding: max(8px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) 8px max(12px, env(safe-area-inset-left)); }
  .back-button { display: flex; }
}
@media (max-height: 480px) {
  .chat-workspace :deep(.composer textarea) { max-height: 72px; }
  .chat-workspace :deep(.composer-hint) { max-height: 36px; overflow-y: auto; }
}
@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) {
  .header-profile, .back-button, .chat-workspace :deep(.composer) { background: var(--surface); -webkit-backdrop-filter: none; backdrop-filter: none; box-shadow: none; border-color: var(--mx-color-glass-accessible-border); }
}
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .header-profile, .back-button, .chat-workspace :deep(.composer) { background: var(--surface); }
}
@media (forced-colors: active) {
  .header-profile, .back-button, .chat-workspace :deep(.composer) { background: Canvas; color: CanvasText; border: 1px solid CanvasText; box-shadow: none; }
}

.chat-workspace.workspace.chrome-in-flow { display: flex; flex-direction: column; overflow-y: auto; }
.chrome-in-flow .workspace-header { position: relative; flex: 0 0 auto; }
.chrome-in-flow :deep(.message-thread) { height: auto; min-height: 96px; flex: 1 0 96px; padding-block: 12px; scroll-padding-block: 12px; }
.chrome-in-flow :deep(.composer-wrap) { position: relative; flex: 0 0 auto; }

.chat-workspace :deep(.attachment-panel .tool-button) { width: 100%; }
.chat-workspace :deep(.composer--burn) { border-color: var(--danger); }
</style>
