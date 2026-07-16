<script setup lang="ts">
import { computed } from 'vue'
import type {
  BroadcastDetail,
  BroadcastPriority,
  BroadcastStatistics,
} from '../../types'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  detail?: BroadcastDetail | null
  statistics?: BroadcastStatistics | null
  loading?: boolean
  confirming?: boolean
  statisticsLoading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  detail: null,
  statistics: null,
  loading: false,
  confirming: false,
  statisticsLoading: false,
})
const emit = defineEmits<{
  confirm: [status: string]
  refreshStats: []
}>()

const broadcast = computed(() => props.detail?.broadcast ?? null)
const receiver = computed(() => props.detail?.receiver ?? null)
const expired = computed(() => {
  if (props.statistics?.expired) return true
  const deadline = broadcast.value?.deadlineAt
  if (!deadline) return false
  const parsed = Date.parse(deadline)
  return !Number.isNaN(parsed) && parsed <= Date.now()
})
const canConfirm = computed(() => Boolean(
  broadcast.value?.confirmationRequired
  && broadcast.value.status === 'ACTIVE'
  && receiver.value?.confirmStatus === 'PENDING'
  && !expired.value,
))
const confirmationProgress = computed(() => {
  const stats = props.statistics
  if (!stats || stats.targetCount === 0) return 0
  return Math.min(100, Math.max(0, Math.round((stats.confirmedCount / stats.targetCount) * 100)))
})
const visibleUnconfirmedIds = computed(() => props.statistics?.unconfirmedUserIds.slice(0, 20) ?? [])
const hiddenUnconfirmedCount = computed(() => Math.max(
  0,
  (props.statistics?.unconfirmedUserIds.length ?? 0) - visibleUnconfirmedIds.value.length,
))

function priorityLabel(priority: BroadcastPriority): string {
  return {
    NORMAL: '普通通知',
    IMPORTANT: '重要通知',
    EMERGENCY: '紧急广播',
  }[priority]
}

function scopeLabel(scope: BroadcastDetail['broadcast']['scopeType']): string {
  return {
    ALL: '全体成员',
    GROUP: '群组成员',
    USERS: '指定成员',
  }[scope]
}

function confirmationLabel(status?: string): string {
  if (!status) return '未送达'
  return {
    PENDING: '等待确认',
    NOT_REQUIRED: '无需确认',
    DELIVERED: '已送达',
    VIEWED: '已查看',
    RECEIVED: '已收到',
    EXECUTED: '已执行',
    NEED_SUPPORT: '需要支援',
    EXPIRED: '已过期',
  }[status] ?? status
}

function formatDateTime(value?: string): string {
  if (!value) return '未设置'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function optionLabel(option: string): string {
  return confirmationLabel(option)
}
</script>

<template>
  <main class="broadcast-workspace" aria-live="polite">
    <div v-if="loading" class="workspace-loading" aria-label="正在载入广播详情">
      <span class="loading-line loading-line--kicker" />
      <span class="loading-line loading-line--title" />
      <span class="loading-line loading-line--body" />
      <span class="loading-line loading-line--body-short" />
    </div>

    <section v-else-if="detail && broadcast" class="broadcast-document" :data-priority="broadcast.priority">
      <span class="document-rail" aria-hidden="true" />

      <header class="document-header">
        <div class="priority-mark" aria-hidden="true"><UiIcon name="bell" :size="22" /></div>
        <div class="heading-copy">
          <p class="priority-kicker">{{ priorityLabel(broadcast.priority) }}</p>
          <h1 class="broadcast-title">{{ broadcast.title }}</h1>
        </div>
        <span v-if="broadcast.status === 'CANCELLED'" class="status-badge status-badge--cancelled">已取消</span>
        <span v-else-if="expired" class="status-badge status-badge--expired">已过期</span>
        <span v-else class="status-badge">进行中</span>
      </header>

      <dl class="broadcast-meta">
        <div class="meta-item">
          <dt class="meta-label">接收范围</dt>
          <dd class="meta-value">{{ scopeLabel(broadcast.scopeType) }}</dd>
        </div>
        <div class="meta-item">
          <dt class="meta-label">发布时间</dt>
          <dd class="meta-value">{{ formatDateTime(broadcast.createTime) }}</dd>
        </div>
        <div class="meta-item">
          <dt class="meta-label">确认截止</dt>
          <dd class="meta-value">{{ broadcast.deadlineAt ? formatDateTime(broadcast.deadlineAt) : '不设截止时间' }}</dd>
        </div>
      </dl>

      <article class="broadcast-content">
        <p>{{ broadcast.content }}</p>
      </article>

      <section v-if="receiver" class="receipt-panel" aria-labelledby="receipt-title">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">我的回执</p>
            <h2 id="receipt-title" class="panel-title">{{ confirmationLabel(receiver.confirmStatus) }}</h2>
          </div>
          <span class="receipt-state" :class="{ 'receipt-state--handled': Boolean(receiver.confirmedAt) }">
            <UiIcon :name="receiver.confirmedAt ? 'check' : 'bell'" :size="15" />
          </span>
        </div>

        <p v-if="receiver.confirmedAt" class="receipt-note">
          已于 {{ formatDateTime(receiver.confirmedAt) }} 提交，重复提交相同结果不会重复计数。
        </p>
        <p v-else-if="expired" class="receipt-note receipt-note--warning">确认时限已结束，当前回执不能再更新。</p>
        <p v-else-if="broadcast.confirmationRequired" class="receipt-note">请选择最符合当前处理进度的一项。</p>
        <p v-else class="receipt-note">这条广播不要求确认，打开详情即表示已查看。</p>

        <div v-if="broadcast.confirmationRequired" class="confirmation-actions">
          <button
            v-for="option in detail.confirmationOptions"
            :key="option"
            class="confirmation-button"
            :class="{
              'confirmation-button--selected': receiver.confirmStatus === option,
              'confirmation-button--support': option === 'NEED_SUPPORT',
            }"
            type="button"
            :disabled="!canConfirm || confirming"
            @click="emit('confirm', option)"
          >
            <UiIcon v-if="receiver.confirmStatus === option" name="check" :size="15" />
            {{ optionLabel(option) }}
          </button>
        </div>
      </section>

      <section v-if="detail.createdByCurrentUser" class="statistics-panel" aria-labelledby="statistics-title">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">实时进度</p>
            <h2 id="statistics-title" class="panel-title">确认统计</h2>
          </div>
          <button
            class="refresh-button"
            type="button"
            :disabled="statisticsLoading"
            aria-label="刷新广播统计"
            @click="emit('refreshStats')"
          >
            <UiIcon name="refresh" :size="16" />
          </button>
        </div>

        <div v-if="statistics" class="statistics-content">
          <div class="progress-heading">
            <strong>{{ confirmationProgress }}%</strong>
            <span>{{ statistics.confirmedCount }} / {{ statistics.targetCount }} 人已确认</span>
          </div>
          <div class="progress-track" aria-hidden="true">
            <span class="progress-value" :style="{ width: `${confirmationProgress}%` }" />
          </div>

          <div class="stat-grid">
            <div class="stat-item"><strong>{{ statistics.targetCount }}</strong><span>目标人数</span></div>
            <div class="stat-item"><strong>{{ statistics.deliveredCount }}</strong><span>已送达</span></div>
            <div class="stat-item"><strong>{{ statistics.viewedCount }}</strong><span>已查看</span></div>
            <div class="stat-item"><strong>{{ statistics.unconfirmedCount }}</strong><span>未确认</span></div>
          </div>

          <div v-if="Object.keys(statistics.confirmationCounts).length" class="response-breakdown">
            <div
              v-for="(count, option) in statistics.confirmationCounts"
              :key="option"
              class="breakdown-row"
            >
              <span>{{ optionLabel(option) }}</span>
              <strong>{{ count }}</strong>
            </div>
          </div>

          <div v-if="statistics.unconfirmedUserIds.length" class="unconfirmed-section">
            <span class="unconfirmed-label">未确认成员</span>
            <div class="user-id-list">
              <span v-for="userId in visibleUnconfirmedIds" :key="userId" class="user-id">#{{ userId }}</span>
              <span v-if="hiddenUnconfirmedCount" class="user-id">另有 {{ hiddenUnconfirmedCount }} 人</span>
            </div>
          </div>
        </div>

        <div v-else class="statistics-empty">
          <span v-if="statisticsLoading">正在汇总接收状态…</span>
          <span v-else>统计数据尚未载入。</span>
        </div>
      </section>
    </section>

    <section v-else class="workspace-empty">
      <span class="empty-symbol"><UiIcon name="bell" :size="28" /></span>
      <p class="empty-kicker">广播中心</p>
      <h2 class="empty-title">选择一条广播</h2>
      <span class="empty-copy">查看通知全文、提交处理结果，或追踪成员确认进度。</span>
    </section>
  </main>
</template>

<style scoped>
.broadcast-workspace {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  padding: 28px;
  color: var(--ink);
  background: var(--surface);
  overflow-y: auto;
}

.broadcast-document {
  position: relative;
  width: min(100%, 850px);
  margin: 0 auto;
  padding: 30px 32px 38px;
  border: 1px solid var(--separator);
  border-radius: 24px;
  background: var(--surface-raise);
  box-shadow: 0 16px 42px color-mix(in srgb, var(--shadow-color) 60%, transparent), inset 0 1px 0 var(--highlight-soft);
  overflow: hidden;
}

.document-rail {
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--blue);
}

.broadcast-document[data-priority="IMPORTANT"] .document-rail { background: #d97706; }
.broadcast-document[data-priority="EMERGENCY"] .document-rail { background: var(--coral); }

.document-header { display: flex; align-items: flex-start; gap: 13px; }
.priority-mark {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 10%, var(--fill));
}
.broadcast-document[data-priority="IMPORTANT"] .priority-mark { color: #d97706; background: color-mix(in srgb, #d97706 10%, var(--fill)); }
.broadcast-document[data-priority="EMERGENCY"] .priority-mark { color: var(--coral); background: color-mix(in srgb, var(--coral) 10%, var(--fill)); }

.heading-copy { min-width: 0; flex: 1; }
.priority-kicker { margin: 1px 0 5px; color: var(--ink-soft); font-size: 11px; font-weight: 700; }
.broadcast-title { margin: 0; font-size: clamp(22px, 3vw, 30px); line-height: 1.2; letter-spacing: -0.045em; }
.status-badge { padding: 5px 9px; border-radius: 999px; color: var(--green); font-size: 10px; font-weight: 700; background: color-mix(in srgb, var(--green) 10%, transparent); }
.status-badge--expired { color: #d97706; background: color-mix(in srgb, #d97706 10%, transparent); }
.status-badge--cancelled { color: var(--ink-soft); background: var(--fill); }

.broadcast-meta {
  display: grid;
  margin: 24px 0 0;
  padding: 14px 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--separator);
  border-bottom: 1px solid var(--separator);
}
.meta-item { min-width: 0; padding: 0 14px; border-right: 1px solid var(--separator); }
.meta-item:first-child { padding-left: 0; }
.meta-item:last-child { padding-right: 0; border-right: 0; }
.meta-label { margin-bottom: 4px; color: var(--ink-faint); font-size: 10px; }
.meta-value { margin: 0; overflow: hidden; font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }

.broadcast-content { min-height: 150px; padding: 30px 4px 34px; }
.broadcast-content p { margin: 0; font-size: 15px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }

.receipt-panel,
.statistics-panel { margin-top: 14px; padding: 20px; border: 1px solid var(--separator); border-radius: 18px; background: var(--surface-tint); }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-kicker { margin: 0 0 4px; color: var(--ink-faint); font-size: 10px; font-weight: 700; }
.panel-title { margin: 0; font-size: 17px; letter-spacing: -0.025em; }
.receipt-state { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 50%; color: #d97706; background: color-mix(in srgb, #d97706 11%, var(--fill)); }
.receipt-state--handled { color: var(--green); background: color-mix(in srgb, var(--green) 11%, var(--fill)); }
.receipt-note { margin: 12px 0 0; color: var(--ink-soft); font-size: 12px; line-height: 1.6; }
.receipt-note--warning { color: #d97706; }
.confirmation-actions { display: flex; margin-top: 15px; flex-wrap: wrap; gap: 8px; }
.confirmation-button {
  display: inline-flex;
  min-height: 40px;
  padding: 0 14px;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--separator);
  border-radius: 12px;
  color: var(--ink);
  font-weight: 620;
  background: var(--surface);
  cursor: pointer;
}
.confirmation-button:hover:not(:disabled) { border-color: color-mix(in srgb, var(--blue) 42%, transparent); background: var(--active); }
.confirmation-button--selected { border-color: color-mix(in srgb, var(--green) 45%, transparent); color: var(--green); background: color-mix(in srgb, var(--green) 9%, transparent); }
.confirmation-button--support:not(:disabled) { color: var(--coral); }
.confirmation-button:disabled { cursor: not-allowed; opacity: 0.58; }

.refresh-button { display: grid; width: 34px; height: 34px; padding: 0; place-items: center; border: 1px solid var(--separator); border-radius: 10px; color: var(--ink-soft); background: var(--surface); cursor: pointer; }
.refresh-button:hover:not(:disabled) { color: var(--blue); background: var(--active); }
.refresh-button:disabled { cursor: wait; opacity: 0.5; }
.statistics-content { margin-top: 17px; }
.progress-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.progress-heading strong { font-size: 26px; letter-spacing: -0.045em; }
.progress-heading span { color: var(--ink-soft); font-size: 11px; }
.progress-track { height: 7px; margin-top: 9px; border-radius: 999px; background: var(--fill); overflow: hidden; }
.progress-value { display: block; height: 100%; border-radius: inherit; background: var(--green); }
.stat-grid { display: grid; margin-top: 17px; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.stat-item { display: grid; min-width: 0; padding: 12px; gap: 3px; border-radius: 12px; background: var(--surface); }
.stat-item strong { font-size: 18px; letter-spacing: -0.03em; }
.stat-item span { color: var(--ink-faint); font-size: 10px; }
.response-breakdown { display: grid; margin-top: 13px; gap: 1px; border-radius: 12px; background: var(--separator); overflow: hidden; }
.breakdown-row { display: flex; min-height: 38px; padding: 0 12px; align-items: center; justify-content: space-between; background: var(--surface); }
.breakdown-row span { color: var(--ink-soft); font-size: 11px; }
.breakdown-row strong { font-size: 12px; }
.unconfirmed-section { margin-top: 15px; }
.unconfirmed-label { color: var(--ink-soft); font-size: 11px; font-weight: 650; }
.user-id-list { display: flex; margin-top: 8px; flex-wrap: wrap; gap: 6px; }
.user-id { padding: 4px 7px; border-radius: 8px; color: var(--ink-soft); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; background: var(--fill); }
.statistics-empty { display: grid; min-height: 90px; place-items: center; color: var(--ink-soft); font-size: 12px; }

.workspace-empty {
  display: grid;
  width: min(100%, 390px);
  min-height: 320px;
  margin: auto;
  padding: 38px;
  place-content: center;
  place-items: center;
  border: 1px solid var(--separator);
  border-radius: 25px;
  text-align: center;
  background: var(--surface-raise);
}
.empty-symbol { display: grid; width: 64px; height: 64px; margin-bottom: 16px; place-items: center; border-radius: 20px; color: var(--coral); background: color-mix(in srgb, var(--coral) 9%, var(--fill)); }
.empty-kicker { margin: 0 0 5px; color: var(--coral); font-size: 10px; font-weight: 700; }
.empty-title { margin: 0; font-size: 21px; letter-spacing: -0.04em; }
.empty-copy { max-width: 290px; margin-top: 8px; color: var(--ink-soft); font-size: 12px; line-height: 1.6; }

.workspace-loading { display: grid; width: min(100%, 850px); margin: 0 auto; padding: 34px; gap: 14px; border: 1px solid var(--separator); border-radius: 24px; }
.loading-line { display: block; height: 18px; border-radius: 7px; background: var(--fill); }
.loading-line--kicker { width: 92px; height: 11px; }
.loading-line--title { width: min(70%, 420px); height: 30px; }
.loading-line--body { width: 100%; margin-top: 30px; }
.loading-line--body-short { width: 72%; }

@media (max-width: 760px) {
  .broadcast-workspace { padding: 0; }
  .broadcast-document { min-height: 100%; padding: 24px 20px calc(100px + env(safe-area-inset-bottom)); border: 0; border-radius: 0; box-shadow: none; }
  .document-header { align-items: center; }
  .priority-mark { width: 42px; height: 42px; }
  .status-badge { display: none; }
  .broadcast-meta { grid-template-columns: 1fr; gap: 10px; }
  .meta-item,
  .meta-item:first-child,
  .meta-item:last-child { display: flex; padding: 0; justify-content: space-between; border-right: 0; }
  .broadcast-content { padding: 24px 2px; }
  .receipt-panel,
  .statistics-panel { padding: 17px; }
  .confirmation-actions { display: grid; grid-template-columns: 1fr; }
  .confirmation-button { justify-content: center; }
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .workspace-empty { width: calc(100% - 36px); margin-top: 28px; }
}
</style>
