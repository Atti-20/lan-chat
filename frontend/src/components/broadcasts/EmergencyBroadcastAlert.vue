<script setup lang="ts">
import { computed } from 'vue'
import type { EmergencyBroadcast } from '../../types'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  broadcast?: EmergencyBroadcast | null
  confirmationOptions?: readonly string[]
  busy?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  broadcast: null,
  confirmationOptions: () => [],
  busy: false,
})
const emit = defineEmits<{
  open: [broadcastId: number]
  confirm: [broadcastId: number, status: string]
  dismiss: [broadcastId: number]
}>()

const visible = computed(() => props.broadcast?.priority === 'EMERGENCY'
  && props.broadcast.status === 'ACTIVE')
const expired = computed(() => {
  const deadline = props.broadcast?.deadlineAt
  if (!deadline) return false
  const parsed = Date.parse(deadline)
  return !Number.isNaN(parsed) && parsed <= Date.now()
})
const resolvedOptions = computed(() => {
  if (props.confirmationOptions.length) return [...props.confirmationOptions]
  const raw = props.broadcast?.confirmationOptions
  if (!raw) return ['RECEIVED', 'EXECUTED', 'NEED_SUPPORT']
  try {
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed) && parsed.every((value) => typeof value === 'string')) return parsed
  } catch {
    return ['RECEIVED', 'EXECUTED', 'NEED_SUPPORT']
  }
  return ['RECEIVED', 'EXECUTED', 'NEED_SUPPORT']
})
const quickConfirmation = computed(() => resolvedOptions.value.includes('RECEIVED')
  ? 'RECEIVED'
  : resolvedOptions.value[0])
const canQuickConfirm = computed(() => Boolean(
  props.broadcast?.confirmationRequired
  && quickConfirmation.value
  && !expired.value,
))

function confirmationLabel(status?: string): string {
  if (!status) return '确认收到'
  return {
    RECEIVED: '确认收到',
    EXECUTED: '确认已执行',
    NEED_SUPPORT: '报告需要支援',
  }[status] ?? `确认：${status}`
}

function formatDeadline(value?: string): string {
  if (!value) return '未设置截止时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '截止时间未知'
  return `请在 ${new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)} 前处理`
}

function confirm(): void {
  if (!props.broadcast || !quickConfirmation.value) return
  emit('confirm', props.broadcast.id, quickConfirmation.value)
}
</script>

<template>
  <div v-if="visible && broadcast" class="alert-backdrop" role="presentation">
    <section
      class="emergency-alert"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="emergency-alert-title"
      aria-describedby="emergency-alert-content"
    >
      <span class="alert-rail" aria-hidden="true" />
      <header class="alert-header">
        <span class="alert-symbol" aria-hidden="true"><UiIcon name="bell" :size="23" /></span>
        <div class="alert-heading">
          <p class="alert-kicker">紧急广播</p>
          <h2 id="emergency-alert-title" class="alert-title">{{ broadcast.title }}</h2>
        </div>
        <button
          v-if="!broadcast.confirmationRequired || expired"
          class="dismiss-button"
          type="button"
          aria-label="关闭紧急广播提醒"
          @click="emit('dismiss', broadcast.id)"
        >
          <UiIcon name="close" :size="16" />
        </button>
      </header>

      <p id="emergency-alert-content" class="alert-content">{{ broadcast.content }}</p>

      <div class="alert-meta">
        <span>{{ formatDeadline(broadcast.deadlineAt) }}</span>
        <span v-if="broadcast.confirmationRequired">需要提交处理结果</span>
        <span v-if="broadcast.bypassMute">已绕过普通免打扰</span>
      </div>

      <p v-if="expired" class="expired-note">确认时限已结束，仍可打开广播查看完整内容。</p>

      <footer class="alert-actions">
        <button class="secondary-button" type="button" @click="emit('open', broadcast.id)">查看完整广播</button>
        <button
          v-if="canQuickConfirm"
          class="primary-button confirm-button"
          type="button"
          :disabled="busy"
          @click="confirm"
        >
          <UiIcon name="check" :size="16" />
          {{ busy ? '正在确认…' : confirmationLabel(quickConfirmation) }}
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.alert-backdrop {
  position: fixed;
  z-index: 160;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: color-mix(in srgb, var(--backdrop) 88%, transparent);
  backdrop-filter: blur(16px) saturate(120%);
  -webkit-backdrop-filter: blur(16px) saturate(120%);
}

.emergency-alert {
  position: relative;
  width: min(100%, 560px);
  padding: 25px 26px 23px 31px;
  border: 1px solid color-mix(in srgb, var(--coral) 22%, var(--glass-border));
  border-radius: 22px;
  color: var(--ink);
  background: var(--surface-raise);
  box-shadow: 0 26px 80px color-mix(in srgb, var(--coral) 16%, var(--shadow-color)), inset 0 1px 0 var(--highlight-soft);
  overflow: hidden;
}

.alert-rail {
  position: absolute;
  inset: 0 auto 0 0;
  width: 6px;
  background: var(--coral);
}

.alert-header { display: flex; align-items: flex-start; gap: 12px; }
.alert-symbol { display: grid; width: 46px; height: 46px; flex: 0 0 auto; place-items: center; border-radius: 15px; color: var(--coral); background: color-mix(in srgb, var(--coral) 11%, var(--fill)); }
.alert-heading { min-width: 0; flex: 1; }
.alert-kicker { margin: 1px 0 4px; color: var(--coral); font-size: 10px; font-weight: 750; letter-spacing: 0.08em; }
.alert-title { margin: 0; font-size: 21px; line-height: 1.25; letter-spacing: -0.035em; }
.dismiss-button { display: inline-flex; min-width: 40px; height: 34px; padding: 0; align-items: center; justify-content: center; border: 0; border-radius: 10px; color: var(--ink-soft); background: var(--fill); cursor: pointer; }
.dismiss-button:hover { background: var(--button-hover); }

.alert-content {
  display: -webkit-box;
  margin: 20px 2px 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 5;
}

.alert-meta { display: flex; margin-top: 17px; flex-wrap: wrap; gap: 7px; }
.alert-meta span { padding: 5px 8px; border-radius: 9px; color: var(--ink-soft); font-size: 10px; font-weight: 600; background: var(--fill); }
.expired-note { margin: 13px 0 0; color: #d97706; font-size: 11px; line-height: 1.5; }
.alert-actions { display: flex; margin-top: 22px; align-items: center; justify-content: flex-end; gap: 9px; }
.confirm-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; background: var(--coral); box-shadow: 0 5px 16px color-mix(in srgb, var(--coral) 26%, transparent), inset 0 1px 0 rgba(255, 255, 255, 0.26); }
.confirm-button:hover { background: color-mix(in srgb, var(--coral) 88%, #000); }

@media (max-width: 560px) {
  .alert-backdrop { padding: 14px; place-items: end center; }
  .emergency-alert { width: 100%; padding: 23px 20px calc(20px + env(safe-area-inset-bottom)) 25px; border-radius: 22px; }
  .alert-actions { display: grid; grid-template-columns: 1fr; }
  .alert-actions .secondary-button,
  .alert-actions .primary-button { width: 100%; }
  .alert-actions .confirm-button { grid-row: 1; }
}
</style>
