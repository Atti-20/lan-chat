<script setup lang="ts">
import { computed } from 'vue'
import UiIcon from '../base/UiIcon.vue'

interface NoticeCard {
  kind?: string
  broadcastId?: number
  title?: string
  summary?: string
  priority?: string
  deadlineAt?: string
  confirmationRequired?: boolean
  sender?: string
  reminder?: string
}

const props = defineProps<{ content?: string }>()
const card = computed<NoticeCard>(() => {
  try {
    const parsed: unknown = JSON.parse(props.content || '{}')
    return parsed && typeof parsed === 'object' ? parsed as NoticeCard : {}
  } catch {
    return { summary: props.content || '你收到一条广播通知。' }
  }
})
const isReminder = computed(() => card.value.kind === 'BROADCAST_REMINDER')
const priorityLabel = computed(() => ({
  EMERGENCY: '紧急', IMPORTANT: '重要', NORMAL: '通知',
}[card.value.priority || 'NORMAL'] || '通知'))
const deadline = computed(() => {
  if (!card.value.deadlineAt) return '未设置处理期限'
  const date = new Date(card.value.deadlineAt)
  return Number.isNaN(date.getTime()) ? '处理期限待确认' : new Intl.DateTimeFormat('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
})
</script>

<template>
  <article class="broadcast-notice" :class="{ 'broadcast-notice--reminder': isReminder }">
    <header>
      <span><UiIcon :name="isReminder ? 'bell' : 'messages'" :size="16" /></span>
      <p>{{ isReminder ? '广播确认提醒' : '广播内容概览' }}</p>
      <small>{{ priorityLabel }}</small>
    </header>
    <strong>{{ card.title || '广播通知' }}</strong>
    <p class="notice-summary">{{ card.reminder || card.summary || '请在广播页面查看完整内容。' }}</p>
    <footer>
      <span v-if="card.confirmationRequired">需要提交结果</span>
      <span>{{ deadline }}</span>
    </footer>
  </article>
</template>

<style scoped>
.broadcast-notice { display: grid; min-width: min(250px, 100%); padding: 11px; gap: 7px; border: 1px solid color-mix(in srgb, var(--blue) 20%, var(--separator)); border-radius: 13px; color: var(--ink); background: color-mix(in srgb, var(--blue) 5%, var(--surface)); }
.broadcast-notice--reminder { border-color: color-mix(in srgb, var(--coral) 24%, var(--separator)); background: color-mix(in srgb, var(--coral) 5%, var(--surface)); }
.broadcast-notice header { display: flex; align-items: center; gap: 6px; }
.broadcast-notice header > span { display: grid; width: 25px; height: 25px; place-items: center; border-radius: 8px; color: var(--accent-text); background: color-mix(in srgb, var(--blue) 12%, var(--surface)); }
.broadcast-notice--reminder header > span { color: var(--danger); background: color-mix(in srgb, var(--coral) 12%, var(--surface)); }
.broadcast-notice header p { flex: 1; margin: 0; color: var(--ink-soft); font-size: var(--font-micro); font-weight: 750; }
.broadcast-notice header small { padding: 3px 5px; border-radius: 6px; color: var(--accent-text); font-size: var(--font-micro); font-weight: 700; background: var(--active); }
.broadcast-notice > strong { font-size: var(--font-body); line-height: 1.35; overflow-wrap: anywhere; }
.notice-summary { display: -webkit-box; margin: 0; overflow: hidden; color: var(--ink-soft); font-size: var(--font-caption); line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.broadcast-notice footer { display: flex; flex-wrap: wrap; gap: 5px; }
.broadcast-notice footer span { padding: 3px 5px; border-radius: 6px; color: var(--ink-faint); font-size: var(--font-micro); background: var(--fill); }
</style>
