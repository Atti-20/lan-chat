<script setup lang="ts">
import { computed, shallowRef, useTemplateRef, watch } from 'vue'
import type { ChatMessage, Conversation, GroupMember } from '../../types'
import { clipboardContainsTable, clipboardTableToImage } from '../../utils/clipboard'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  conversation: Conversation
  replyTo?: ChatMessage | null
  connected: boolean
  uploading?: boolean
  transferLabel?: string
  writable?: boolean
  fileAllowed?: boolean
  statusLabel?: string
  members?: readonly GroupMember[]
  currentUserId?: number
}

const props = withDefaults(defineProps<Props>(), {
  replyTo: null,
  uploading: false,
  transferLabel: '',
  writable: true,
  fileAllowed: true,
  statusLabel: '',
  members: () => [],
  currentUserId: undefined,
})
const emit = defineEmits<{
  send: [content: string, burn: boolean, mentionUserIds?: string]
  composerSubmitted: []
  typing: []
  file: [file: File]
  cancelReply: []
}>()
const content = shallowRef('')
const burn = shallowRef(false)
const fileRef = useTemplateRef<HTMLInputElement>('fileInput')
const imageRef = useTemplateRef<HTMLInputElement>('imageInput')
const textareaRef = useTemplateRef<HTMLTextAreaElement>('textarea')
const pasting = shallowRef(false)
const mentionOpen = shallowRef(false)
const mentionAll = shallowRef(false)
const mentionedUserIds = shallowRef<number[]>([])
let typingTimer: number | null = null

const isGroup = computed(() => props.conversation.kind === 'group')
const canMentionAll = computed(() => props.members.some((member) => (
  member.userId === props.currentUserId && member.role >= 1
)))
const mentionableMembers = computed(() => props.members.filter((member) => member.userId !== props.currentUserId))

function submit(): void {
  if (!props.writable) return
  if (mentionAll.value && !canMentionAll.value) clearMentionAll()
  const value = content.value.trim()
  if (!value) return
  const mentionUserIds = mentionAll.value
    ? 'ALL'
    : mentionedUserIds.value.length ? mentionedUserIds.value.join(',') : undefined
  emit('send', value, burn.value, mentionUserIds)
  content.value = ''
  burn.value = false
  mentionAll.value = false
  mentionedUserIds.value = []
  mentionOpen.value = false
  emit('cancelReply')
  if (textareaRef.value) textareaRef.value.style.height = 'auto'
  // Tapping the send control on iOS does not necessarily blur the textarea.
  // Keep the keyboard open for consecutive messages, but let the viewport
  // owner re-anchor the document after this local submit.
  emit('composerSubmitted')
}

function onInput(): void {
  const textarea = textareaRef.value
  if (textarea) {
    textarea.style.height = 'auto'
    textarea.style.height = `${Math.min(textarea.scrollHeight, 132)}px`
  }
  reconcileMentions()
  if (!props.writable || typingTimer !== null) return
  emit('typing')
  typingTimer = window.setTimeout(() => { typingTimer = null }, 1800)
}

function insertMentionToken(token: string): void {
  const separator = content.value && !/\s$/.test(content.value) ? ' ' : ''
  if (!hasMentionToken(token)) content.value = `${content.value}${separator}${token} `
  scheduleMentionInputSync()
}

function hasMentionToken(token: string): boolean {
  return new RegExp(`(^|\\s)${escapeRegExp(token)}(?=\\s|$)`).test(content.value)
}

function removeMentionToken(token: string): void {
  const tokenPattern = new RegExp(`(^|\\s)${escapeRegExp(token)}(?=\\s|$)`, 'g')
  content.value = content.value
    .replace(tokenPattern, '$1')
    .replace(/[ \\t]{2,}/g, ' ')
  scheduleMentionInputSync()
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function scheduleMentionInputSync(): void {
  requestAnimationFrame(() => {
    textareaRef.value?.focus()
    onInput()
  })
}

function selectMentionAll(): void {
  if (mentionAll.value) {
    clearMentionAll()
    return
  }
  mentionedUserIds.value.forEach((userId) => {
    const member = props.members.find((item) => item.userId === userId)
    if (member) removeMentionToken(`@${member.nickname}`)
  })
  mentionedUserIds.value = []
  mentionAll.value = true
  insertMentionToken('@所有人')
}

function clearMentionAll(): void {
  mentionAll.value = false
  removeMentionToken('@所有人')
}

function selectMentionMember(member: GroupMember): void {
  if (mentionAll.value) {
    mentionAll.value = false
    removeMentionToken('@所有人')
  }
  const selected = !mentionedUserIds.value.includes(member.userId)
  mentionedUserIds.value = selected
    ? [...mentionedUserIds.value, member.userId]
    : mentionedUserIds.value.filter((id) => id !== member.userId)
  if (selected) insertMentionToken(`@${member.nickname}`)
  else removeMentionToken(`@${member.nickname}`)
}

function reconcileMentions(): void {
  if (mentionAll.value && !hasMentionToken('@所有人')) mentionAll.value = false
  mentionedUserIds.value = mentionedUserIds.value.filter((userId) => {
    const member = props.members.find((item) => item.userId === userId)
    return Boolean(member && hasMentionToken(`@${member.nickname}`))
  })
}

watch(canMentionAll, (allowed) => {
  if (!allowed && mentionAll.value) clearMentionAll()
})

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

async function onPaste(event: ClipboardEvent): Promise<void> {
  if (!props.connected
    || !props.writable
    || !props.fileAllowed
    || props.uploading
    || pasting.value) return
  const clipboard = event.clipboardData
  if (!clipboard) return

  const imageItem = Array.from(clipboard.items).find((item) => item.kind === 'file' && item.type.startsWith('image/'))
  const html = clipboard.getData('text/html')
  const text = clipboard.getData('text/plain')
  const tablePaste = clipboardContainsTable(html, text)
  if (!imageItem && !tablePaste) return

  event.preventDefault()
  pasting.value = true
  try {
    const image = imageItem?.getAsFile()
    if (image) {
      emit('file', image)
      return
    }

    const tableImage = await clipboardTableToImage(html, text)
    if (tableImage && !props.uploading) emit('file', tableImage)
    else insertPastedText(text)
  } finally {
    pasting.value = false
  }
}

function insertPastedText(value: string): void {
  const textarea = textareaRef.value
  if (!textarea || !value) return
  const start = textarea.selectionStart ?? content.value.length
  const end = textarea.selectionEnd ?? start
  const next = `${content.value.slice(0, start)}${value}${content.value.slice(end)}`.slice(0, 4000)
  content.value = next
  requestAnimationFrame(() => {
    if (!textareaRef.value) return
    const caret = Math.min(start + value.length, next.length)
    textareaRef.value.setSelectionRange(caret, caret)
    onInput()
  })
}

function chooseFile(target: HTMLInputElement | null): void {
  target?.click()
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file && !props.uploading) emit('file', file)
  input.value = ''
}
</script>

<template>
  <footer class="composer-wrap">
    <div v-if="replyTo" class="reply-bar">
      <span class="reply-mark" aria-hidden="true">↩</span>
      <span><strong>回复消息</strong><small>{{ replyTo.content || '附件消息' }}</small></span>
      <button type="button" aria-label="取消回复" @click="emit('cancelReply')"><UiIcon name="close" :size="16" /></button>
    </div>

    <div class="composer glass-surface apple-content-surface" :class="{ 'composer--burn': burn }">
      <div class="composer-tools">
        <div v-if="isGroup" class="mention-menu-wrap">
          <button
            class="tool-button mention-button"
            :class="{ 'mention-button--active': mentionOpen || mentionAll || mentionedUserIds.length }"
            type="button"
            aria-label="@群成员"
            :aria-expanded="mentionOpen"
            @click="mentionOpen = !mentionOpen"
          >@</button>
          <div v-if="mentionOpen" class="mention-menu" role="menu" aria-label="选择要@的群成员">
            <button
              v-if="canMentionAll"
              type="button"
              class="mention-option mention-option--all"
              :class="{ 'mention-option--selected': mentionAll }"
              role="menuitemcheckbox"
              :aria-checked="mentionAll"
              @click="selectMentionAll"
            >@所有人</button>
            <button
              v-for="member in mentionableMembers"
              :key="member.userId"
              type="button"
              class="mention-option"
              :class="{ 'mention-option--selected': mentionedUserIds.includes(member.userId) }"
              role="menuitemcheckbox"
              :aria-checked="mentionedUserIds.includes(member.userId)"
              @click="selectMentionMember(member)"
            >@{{ member.nickname }}</button>
          </div>
        </div>
        <button class="tool-button" type="button" aria-label="发送图片" :disabled="uploading || pasting || !connected || !writable || !fileAllowed" :title="!fileAllowed ? '房间不允许上传附件' : connected ? '' : '连接节点后可上传图片'" @click="chooseFile(imageRef)">
          <UiIcon name="image" :size="20" />
        </button>
        <button class="tool-button" type="button" aria-label="发送文件" :disabled="uploading || pasting || !connected || !writable || !fileAllowed" :title="!fileAllowed ? '房间不允许上传附件' : connected ? '' : '连接节点后可上传文件'" @click="chooseFile(fileRef)">
          <UiIcon name="paperclip" :size="20" />
        </button>
        <button class="tool-button burn-button" :class="{ 'burn-button--active': burn }" type="button" :disabled="!writable" :aria-pressed="burn" aria-label="切换阅后即焚" @click="burn = !burn">
          <UiIcon name="flame" :size="20" />
        </button>
      </div>

      <textarea
        ref="textarea"
        v-model="content"
        rows="1"
        maxlength="4000"
        :disabled="!writable"
        :placeholder="!writable ? (statusLabel || '当前会话为只读状态') : connected ? `发消息给 ${conversation.name}` : '离线消息将保存在本机，连接恢复后自动发送'"
        @input="onInput"
        @keydown="onKeydown"
        @paste="onPaste"
      />

      <button class="send-button" type="button" :disabled="!writable || !content.trim()" aria-label="发送消息" @click="submit">
        <UiIcon name="send" :size="22" />
      </button>

      <input ref="imageInput" class="sr-only" type="file" accept="image/*" @change="onFileChange" />
      <input ref="fileInput" class="sr-only" type="file" @change="onFileChange" />
    </div>
    <p v-if="statusLabel || transferLabel || !connected" class="composer-hint">{{ statusLabel || transferLabel || '离线可发送文本 · 文件任务等待连接' }}</p>
  </footer>
</template>

<style scoped>
.composer-wrap { min-width: 0; min-height: 0; padding: 9px clamp(14px, 2.5vw, 24px) 13px; border-top: 1px solid var(--separator); background: var(--surface-glass); }
.composer {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  min-height: 54px;
  padding: 6px 8px;
  align-items: end;
  gap: 7px;
  border-radius: 18px;
  transition: border-color 200ms ease, box-shadow 200ms ease;
  background: var(--surface-tint);
  box-shadow: 0 5px 18px var(--shadow-color), inset 0 1px 0 var(--highlight);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
}
.composer:focus-within { border-color: rgba(0, 122, 255, .28); }
.composer--burn { border-color: rgba(255, 59, 48, 0.3); box-shadow: 0 5px 18px rgba(255, 59, 48, 0.08); }
.composer-tools { display: flex; align-self: end; gap: 2px; }
.mention-menu-wrap { position: relative; }
.mention-button { font-size: 19px; font-weight: 750; line-height: 1; }
.mention-button--active { color: var(--accent-text); background: var(--active); }
.mention-menu { position: absolute; z-index: 30; bottom: calc(100% + 8px); left: 0; display: grid; width: min(230px, calc(100vw - 30px)); max-height: 245px; padding: 6px; gap: 2px; overflow-y: auto; border: 1px solid var(--separator); border-radius: 14px; background: var(--surface-raise); box-shadow: 0 12px 30px var(--shadow-color); }
.mention-option { min-height: 40px; padding: 0 10px; border: 0; border-radius: 9px; color: var(--ink); font: inherit; font-size: var(--font-caption); text-align: left; background: transparent; cursor: pointer; }
.mention-option:hover, .mention-option--selected { color: var(--accent-text); background: var(--active); }
.mention-option--all { font-weight: 750; }
.tool-button { display: grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 0; border-radius: var(--radius-control); color: var(--ink-soft); background: transparent; cursor: pointer; }
.tool-button:hover { color: var(--accent-text); background: rgba(0, 122, 255, 0.08); }
.tool-button:disabled { cursor: not-allowed; opacity: .42; }
.tool-button .ui-icon { width: 20px; }
.burn-button--active { color: #fff; background: var(--danger-bg); box-shadow: none; }
.composer textarea { display: block; width: 100%; min-width: 0; min-height: 40px; max-height: 132px; padding: 8px 4px; align-self: end; resize: none; overflow-y: auto; border: 0; outline: none; color: var(--ink); line-height: 1.5; background: transparent; }
.composer textarea::placeholder { color: color-mix(in srgb, var(--ink-faint) 86%, transparent); font-size: .92em; font-weight: 400; }
.composer textarea:disabled { cursor: not-allowed; opacity: .72; }
.send-button { display: grid; width: 40px; height: 40px; padding: 0; align-self: end; place-items: center; border: 0; border-radius: 50%; color: #fff; cursor: pointer; transition: 180ms var(--ease-liquid); flex: 0 0 auto; background: var(--action-bg); box-shadow: 0 4px 12px rgba(0, 122, 255, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.28); }
.send-button:hover { transform: translateY(-1px); background: color-mix(in srgb, var(--action-bg) 88%, #000); }
.send-button:disabled { opacity: .36; filter: grayscale(.5); cursor: not-allowed; transform: none; }
.send-button .ui-icon { width: 22px; }
.composer-hint { margin: 6px 8px 0; color: var(--ink-faint); font-size: var(--font-micro); text-align: right; }
.reply-bar { display: flex; min-height: 42px; padding: 7px 12px; margin: 0 8px 7px; align-items: center; gap: 10px; border: 1px solid var(--separator); border-radius: var(--radius-control); color: var(--ink-soft); background: var(--fill); }
.reply-mark { color: var(--accent-text); font-size: var(--font-title-sm); }
.reply-bar > span:nth-child(2) { display: grid; min-width: 0; flex: 1; gap: 2px; }
.reply-bar strong { color: var(--ink); font-size: var(--font-caption); }
.reply-bar small { overflow: hidden; font-size: var(--font-micro); text-overflow: ellipsis; white-space: nowrap; }
.reply-bar button { padding: 0; border: 0; color: var(--ink-soft); font-size: var(--font-title); background: transparent; cursor: pointer; }

@media (max-width: 760px) {
  .composer-wrap {
    padding: 7px max(9px, env(safe-area-inset-right)) max(9px, env(safe-area-inset-bottom)) max(9px, env(safe-area-inset-left));
  }
  .composer { padding: 5px 6px; gap: 3px; border-radius: 17px; }
  .composer-tools { gap: 0; }
  .tool-button { width: 32px; height: 36px; }
  .composer textarea { min-height: 38px; padding-inline: 3px; font-size: var(--font-subtitle); }
  .send-button { width: 38px; height: 38px; }
  .composer-hint { display: none; }
}
</style>
