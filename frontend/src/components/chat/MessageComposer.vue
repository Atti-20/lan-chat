<script setup lang="ts">
import { shallowRef, useTemplateRef } from 'vue'
import type { ChatMessage, Conversation } from '../../types'

interface Props {
  conversation: Conversation
  replyTo?: ChatMessage | null
  connected: boolean
  uploading?: boolean
}

withDefaults(defineProps<Props>(), {
  replyTo: null,
  uploading: false,
})
const emit = defineEmits<{
  send: [content: string, burn: boolean]
  typing: []
  file: [file: File]
  cancelReply: []
}>()
const content = shallowRef('')
const burn = shallowRef(false)
const fileRef = useTemplateRef<HTMLInputElement>('fileInput')
const imageRef = useTemplateRef<HTMLInputElement>('imageInput')
const textareaRef = useTemplateRef<HTMLTextAreaElement>('textarea')
let typingTimer: number | null = null

function submit(): void {
  const value = content.value.trim()
  if (!value) return
  emit('send', value, burn.value)
  content.value = ''
  burn.value = false
  emit('cancelReply')
  if (textareaRef.value) textareaRef.value.style.height = 'auto'
}

function onInput(): void {
  const textarea = textareaRef.value
  if (textarea) {
    textarea.style.height = 'auto'
    textarea.style.height = `${Math.min(textarea.scrollHeight, 132)}px`
  }
  if (typingTimer !== null) return
  emit('typing')
  typingTimer = window.setTimeout(() => { typingTimer = null }, 1800)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function chooseFile(target: HTMLInputElement | null): void {
  target?.click()
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) emit('file', file)
  input.value = ''
}
</script>

<template>
  <footer class="composer-wrap">
    <div v-if="replyTo" class="reply-bar">
      <span class="reply-mark" aria-hidden="true">↩</span>
      <span><strong>回复消息</strong><small>{{ replyTo.content || '附件消息' }}</small></span>
      <button type="button" aria-label="取消回复" @click="emit('cancelReply')">×</button>
    </div>

    <div class="composer glass-surface" :class="{ 'composer--burn': burn }">
      <div class="composer-tools">
        <button class="tool-button" type="button" aria-label="发送图片" :disabled="uploading" @click="chooseFile(imageRef)">
          <svg viewBox="0 0 24 24" fill="none"><rect x="3" y="4" width="18" height="16" rx="3" stroke="currentColor" stroke-width="1.8"/><circle cx="9" cy="10" r="2" stroke="currentColor" stroke-width="1.8"/><path d="m5 18 5-5 3 3 2-2 4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button class="tool-button" type="button" aria-label="发送文件" :disabled="uploading" @click="chooseFile(fileRef)">
          <svg viewBox="0 0 24 24" fill="none"><path d="M8 12.5 14.4 6a3 3 0 0 1 4.2 4.2L10 18.8a5 5 0 1 1-7-7L11.4 3.4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button class="tool-button burn-button" :class="{ 'burn-button--active': burn }" type="button" :aria-pressed="burn" aria-label="切换阅后即焚" @click="burn = !burn">
          <svg viewBox="0 0 24 24" fill="none"><path d="M13 3s1 4-2 6c-2.7 1.8-4 4-4 6.5A5 5 0 0 0 12 21a5.5 5.5 0 0 0 5.5-5.5C17.5 10 13 8 13 3Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M12 13c1 1 1.5 2 1.5 3.2A1.8 1.8 0 0 1 11.7 18 1.7 1.7 0 0 1 10 16.2c0-1 .6-1.8 2-3.2Z" fill="currentColor"/></svg>
        </button>
      </div>

      <textarea
        ref="textarea"
        v-model="content"
        rows="1"
        maxlength="4000"
        :placeholder="connected ? `发消息给 ${conversation.name}` : '正在恢复实时连接…'"
        :disabled="!connected"
        @input="onInput"
        @keydown="onKeydown"
      />

      <button class="send-button" type="button" :disabled="!content.trim() || !connected" aria-label="发送消息" @click="submit">
        <svg viewBox="0 0 24 24" fill="none"><path d="m4 5 17 7-17 7 3-7-3-7Z" fill="currentColor"/><path d="M7 12h13" stroke="white" stroke-width="1.5" stroke-linecap="round"/></svg>
      </button>

      <input ref="imageInput" class="sr-only" type="file" accept="image/*" @change="onFileChange" />
      <input ref="fileInput" class="sr-only" type="file" @change="onFileChange" />
    </div>
    <p class="composer-hint">Enter 发送 · Shift + Enter 换行</p>
  </footer>
</template>

<style scoped>
.composer-wrap { padding: 8px clamp(14px, 2.5vw, 30px) 14px; }
.composer { display: flex; min-height: 58px; padding: 7px 8px; align-items: flex-end; gap: 7px; border-radius: 21px 21px 21px 11px; transition: border-color 200ms ease, box-shadow 200ms ease; }
.composer--burn { border-color: rgba(255,107,107,.44); box-shadow: inset 0 1px 0 rgba(255,255,255,.9), 0 15px 34px rgba(255,107,107,.12); }
.composer-tools { display: flex; padding-bottom: 2px; gap: 2px; }
.tool-button { display: grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 0; border-radius: 12px; color: #5e7892; background: transparent; cursor: pointer; }
.tool-button:hover { color: var(--blue); background: rgba(213,233,251,.58); }
.tool-button svg { width: 20px; }
.burn-button--active { color: white; background: linear-gradient(145deg, #ff7a75, #f2445c); box-shadow: 0 7px 14px rgba(242,68,92,.2); }
.composer textarea { min-width: 0; min-height: 40px; max-height: 132px; padding: 10px 4px 8px; flex: 1; resize: none; border: 0; outline: none; color: var(--ink); line-height: 1.5; background: transparent; }
.composer textarea::placeholder { color: #8a9bad; }
.send-button { display: grid; width: 42px; height: 42px; padding: 0; flex: 0 0 auto; place-items: center; border: 0; border-radius: 15px 15px 15px 7px; color: white; background: linear-gradient(145deg, #2094ff, #0878ef 70%, #6464e9); box-shadow: 0 9px 18px rgba(10,132,255,.25), inset 0 1px 0 rgba(255,255,255,.4); cursor: pointer; transition: 180ms var(--ease-liquid); }
.send-button:hover { transform: translateY(-2px) rotate(-2deg); }
.send-button:disabled { opacity: .36; filter: grayscale(.5); cursor: not-allowed; transform: none; }
.send-button svg { width: 23px; }
.composer-hint { margin: 6px 8px 0; color: #8a9bad; font-size: 9px; text-align: right; }
.reply-bar { display: flex; min-height: 42px; padding: 7px 12px; margin: 0 8px 7px; align-items: center; gap: 10px; border: 1px solid rgba(255,255,255,.62); border-radius: 15px 15px 8px 8px; color: var(--ink-soft); background: rgba(255,255,255,.42); }
.reply-mark { color: var(--blue); font-size: 18px; }
.reply-bar > span:nth-child(2) { display: grid; min-width: 0; flex: 1; gap: 2px; }
.reply-bar strong { color: var(--ink); font-size: 10px; }
.reply-bar small { overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.reply-bar button { border: 0; color: #718398; font-size: 20px; background: transparent; cursor: pointer; }

@media (max-width: 760px) {
  .composer-wrap { padding: 6px 9px 88px; }
  .composer { align-items: center; }
  .composer-tools { gap: 0; }
  .tool-button { width: 32px; }
  .burn-button { display: none; }
  .composer-hint { display: none; }
}

.composer-wrap { padding: 9px clamp(14px, 2.5vw, 24px) 13px; border-top: 1px solid var(--separator); background: rgba(255, 255, 255, 0.9); }
.composer {
  min-height: 54px;
  border-radius: 18px;
  background: rgba(247, 247, 249, 0.72);
  box-shadow: 0 5px 18px rgba(29, 29, 31, 0.07), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
}
.composer--burn { border-color: rgba(255, 59, 48, 0.3); box-shadow: 0 5px 18px rgba(255, 59, 48, 0.08); }
.tool-button { color: var(--ink-soft); border-radius: 10px; }
.tool-button:hover { color: var(--blue); background: rgba(0, 122, 255, 0.08); }
.burn-button--active { color: #fff; background: var(--coral); box-shadow: none; }
.send-button {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--blue);
  box-shadow: 0 4px 12px rgba(0, 122, 255, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.28);
}
.send-button:hover { transform: none; background: #0874e8; }
.composer-hint { color: var(--ink-faint); }
.reply-bar { border-color: var(--separator); border-radius: 12px; background: var(--fill); }

@media (max-width: 760px) {
  .composer-wrap { padding: 7px 9px max(10px, env(safe-area-inset-bottom)); }
  .composer { border-radius: 17px; }
}
</style>
