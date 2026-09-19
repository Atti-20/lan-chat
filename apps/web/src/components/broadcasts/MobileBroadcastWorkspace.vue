<script setup lang="ts">
import { computed } from 'vue'
import type { BroadcastCompletePayload, BroadcastDetail, BroadcastStatistics } from '../../types'
import UiIcon from '../base/UiIcon.vue'
import BroadcastCompletionPanel from './BroadcastCompletionPanel.vue'
import BroadcastEvidenceImage from './BroadcastEvidenceImage.vue'

interface Props {
  detail?: BroadcastDetail | null
  statistics?: BroadcastStatistics | null
  loading?: boolean
  confirming?: boolean
  canCancel?: boolean
  cancelling?: boolean
  canDelete?: boolean
  deleting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  detail: null,
  statistics: null,
  loading: false,
  confirming: false,
  canCancel: false,
  cancelling: false,
  canDelete: false,
  deleting: false,
})
const emit = defineEmits<{
  back: []
  complete: [payload: BroadcastCompletePayload]
  cancel: []
  delete: []
}>()

const broadcast = computed(() => props.detail?.broadcast ?? null)
const receiver = computed(() => props.detail?.receiver ?? null)
const expired = computed(() => {
  const deadline = broadcast.value?.deadlineAt
  return Boolean(deadline && !Number.isNaN(Date.parse(deadline)) && Date.parse(deadline) <= Date.now())
})
const completionPending = computed(() => Boolean(
  receiver.value
  && broadcast.value?.confirmationRequired
  && !receiver.value.confirmedAt
  && broadcast.value.status === 'ACTIVE'
  && !expired.value,
))
const statusLabel = computed(() => {
  if (!broadcast.value) return ''
  if (broadcast.value.status === 'CANCELLED') return '已撤销'
  if (receiver.value?.confirmedAt) return '我已完成'
  if (broadcast.value.status === 'COMPLETED') return '已完成'
  if (expired.value) return '已过期'
  return '进行中'
})

function formatDate(value?: string): string {
  if (!value) return '未设置'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(parsed)
}

function priorityLabel(value?: string): string {
  return { EMERGENCY: '紧急广播', IMPORTANT: '重要广播', NORMAL: '广播通知' }[value || 'NORMAL'] || '广播通知'
}
</script>

<template>
  <main class="mobile-broadcast" aria-live="polite">
    <header class="mobile-broadcast-nav">
      <button type="button" class="nav-back" aria-label="返回广播列表" @click="emit('back')">
        <UiIcon name="back" :size="21" />
        <span>广播</span>
      </button>
      <strong>详情</strong>
      <span class="nav-balance" aria-hidden="true" />
    </header>

    <div v-if="loading" class="mobile-loading" aria-label="正在载入广播详情">
      <span /><span /><span />
    </div>

    <div v-else-if="detail && broadcast" class="mobile-scroll">
      <section class="mobile-hero" :data-priority="broadcast.priority">
        <p>{{ priorityLabel(broadcast.priority) }}</p>
        <h1>{{ broadcast.title }}</h1>
        <span class="mobile-status" :class="{ 'mobile-status--done': receiver?.confirmedAt }">{{ statusLabel }}</span>
      </section>

      <section class="mobile-metadata" aria-label="广播信息">
        <div><span>{{ detail.createdByCurrentUser ? '接收范围' : '发送者' }}</span><strong>{{ detail.createdByCurrentUser ? broadcast.scopeType === 'ALL' ? '全体成员' : '指定成员' : (detail.sender.nickname || detail.sender.username) }}</strong></div>
        <div><span>发布时间</span><strong>{{ formatDate(broadcast.createTime) }}</strong></div>
        <div><span>处理期限</span><strong>{{ broadcast.deadlineAt ? formatDate(broadcast.deadlineAt) : '不设期限' }}</strong></div>
      </section>

      <article class="mobile-content">
        <p>{{ broadcast.content }}</p>
        <div v-if="detail.contentEvidence?.imageUrls.length" class="mobile-evidence">
          <BroadcastEvidenceImage
            v-for="url in detail.contentEvidence.imageUrls"
            :key="url"
            :source="url"
            alt="广播附件"
          />
        </div>
      </article>

      <section v-if="receiver" class="mobile-receipt" :class="{ 'mobile-receipt--pending': completionPending }">
        <div class="receipt-heading">
          <span><UiIcon :name="receiver.confirmedAt ? 'check' : 'bell'" :size="18" /></span>
          <div>
            <p>{{ completionPending ? '处理结果' : '我的回执' }}</p>
            <h2>{{ receiver.confirmedAt ? '已提交完成结果' : broadcast.confirmationRequired ? '等待提交' : '无需回执' }}</h2>
          </div>
        </div>
        <p v-if="receiver.confirmedAt">已于 {{ formatDate(receiver.confirmedAt) }} 提交；本条广播已从你的待处理列表移除。</p>
        <p v-else-if="expired">确认时限已结束，无法再提交处理结果。</p>
        <p v-else-if="broadcast.confirmationRequired">请在此处提交完成结果；不会误触发为“已收到”。</p>
        <BroadcastCompletionPanel
          v-if="completionPending"
          :broadcast="broadcast"
          :submitting="confirming"
          @complete="emit('complete', $event)"
        />
      </section>

      <section v-if="detail.createdByCurrentUser && statistics" class="mobile-progress">
        <p>接收进度</p>
        <strong>{{ statistics.confirmedCount }} / {{ statistics.targetCount }}</strong>
        <span>人已提交处理结果</span>
      </section>

      <section v-if="canCancel || canDelete" class="mobile-management">
        <button v-if="canCancel && broadcast.status === 'ACTIVE'" type="button" @click="emit('cancel')">{{ cancelling ? '撤销中…' : '撤销广播' }}</button>
        <button v-if="canDelete && broadcast.status === 'CANCELLED'" type="button" class="delete" @click="emit('delete')">{{ deleting ? '删除中…' : '永久删除' }}</button>
      </section>
    </div>
  </main>
</template>

<style scoped>
.mobile-broadcast { display: grid; min-width: 0; min-height: 0; height: 100%; grid-template-rows: auto minmax(0, 1fr); color: var(--ink); background: var(--surface); }
.mobile-broadcast-nav { display: grid; min-height: max(56px, calc(48px + env(safe-area-inset-top))); padding: env(safe-area-inset-top) max(12px, env(safe-area-inset-right)) 0 max(12px, env(safe-area-inset-left)); grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; border-bottom: 1px solid var(--separator); background: var(--surface-glass); }
.mobile-broadcast-nav > strong { font-size: var(--font-body-lg); letter-spacing: -.02em; }
.nav-back { display: inline-flex; min-width: 44px; min-height: 44px; padding: 0 6px; align-items: center; gap: 3px; justify-self: start; border: 0; border-radius: var(--radius-control); color: var(--accent-text); font-size: var(--font-body); background: transparent; cursor: pointer; }
.nav-balance { width: 44px; height: 44px; justify-self: end; }
.mobile-scroll { min-height: 0; padding: 16px max(16px, env(safe-area-inset-right)) max(28px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left)); overflow-y: auto; overscroll-behavior: contain; }
.mobile-hero { position: relative; padding: 16px; border-radius: 18px; color: #fff; background: linear-gradient(135deg, #007aff, #5856d6); }
.mobile-hero[data-priority='IMPORTANT'] { background: linear-gradient(135deg, #ff9500, #ff6b35); }
.mobile-hero[data-priority='EMERGENCY'] { background: linear-gradient(135deg, #ff3b30, #d70015); }
.mobile-hero p { margin: 0 0 7px; font-size: var(--font-caption); font-weight: 750; opacity: .82; }
.mobile-hero h1 { max-width: calc(100% - 82px); margin: 0; font-size: 23px; line-height: 1.25; letter-spacing: -.035em; overflow-wrap: anywhere; }
.mobile-status { position: absolute; right: 14px; bottom: 14px; padding: 5px 8px; border-radius: 999px; font-size: var(--font-caption); font-weight: 700; background: rgba(255,255,255,.18); }
.mobile-status--done { background: rgba(18, 91, 50, .38); }
.mobile-metadata { display: grid; margin: 14px 0; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--separator); border-radius: 16px; background: var(--surface-tint); }
.mobile-metadata div { display: grid; min-width: 0; padding: 12px 10px; gap: 4px; }
.mobile-metadata div + div { border-left: 1px solid var(--separator); }
.mobile-metadata span { color: var(--ink-faint); font-size: var(--font-micro); }
.mobile-metadata strong { overflow: hidden; font-size: var(--font-caption); line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.mobile-content { padding: 4px 2px 18px; }
.mobile-content > p { margin: 0; font-size: var(--font-body-lg); line-height: 1.78; white-space: pre-wrap; overflow-wrap: anywhere; }
.mobile-evidence { display: grid; margin-top: 14px; gap: 10px; }
.mobile-receipt, .mobile-progress { margin-top: 14px; padding: 15px; border: 1px solid var(--separator); border-radius: 18px; background: var(--surface-tint); }
.mobile-receipt--pending { border-color: color-mix(in srgb, var(--green) 32%, var(--separator)); }
.receipt-heading { display: flex; align-items: center; gap: 10px; }
.receipt-heading > span { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 12px; color: var(--success); background: color-mix(in srgb, var(--green) 12%, var(--surface)); }
.receipt-heading p, .mobile-progress p { margin: 0 0 2px; color: var(--ink-faint); font-size: var(--font-caption); font-weight: 650; }
.receipt-heading h2 { margin: 0; font-size: var(--font-body-lg); letter-spacing: -.02em; }
.mobile-receipt > p { margin: 13px 0 0; color: var(--ink-soft); font-size: var(--font-caption); line-height: 1.55; }
.mobile-progress { display: grid; grid-template-columns: auto auto 1fr; align-items: baseline; gap: 7px; }
.mobile-progress strong { font-size: var(--font-title); }
.mobile-progress span { color: var(--ink-soft); font-size: var(--font-caption); }
.mobile-management { display: grid; margin-top: 18px; gap: 9px; }
.mobile-management button { min-height: 48px; border: 1px solid color-mix(in srgb, var(--danger) 32%, var(--separator)); border-radius: 14px; color: var(--danger); font: inherit; font-weight: 700; background: transparent; cursor: pointer; }
.mobile-management .delete { color: #fff; background: var(--danger-bg); }
.mobile-loading { display: grid; padding: 22px 16px; align-content: start; gap: 12px; }
.mobile-loading span { height: 56px; border-radius: 16px; background: var(--fill); animation: pulse 1.1s ease-in-out infinite alternate; }
.mobile-loading span:first-child { height: 132px; }
@keyframes pulse { to { opacity: .45; } }
@media (max-width: 380px) { .mobile-metadata { grid-template-columns: 1fr; } .mobile-metadata div + div { border-top: 1px solid var(--separator); border-left: 0; } }
</style>
