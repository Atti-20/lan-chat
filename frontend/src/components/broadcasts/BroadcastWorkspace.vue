<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import type {
  BroadcastDetail,
  BroadcastPriority,
  BroadcastStatistics,
  Friend,
} from '../../types'
import UiIcon from '../base/UiIcon.vue'
import UserAvatar from '../base/UserAvatar.vue'
import BroadcastCompletionPanel from './BroadcastCompletionPanel.vue'
import BroadcastTargetEditor from './BroadcastTargetEditor.vue'
import { api } from '../../services/api'
import type {
  BroadcastCompletePayload,
  BroadcastRecipientDetail,
  BroadcastTargetCandidate,
} from '../../types'

interface Props {
  detail?: BroadcastDetail | null
  statistics?: BroadcastStatistics | null
  loading?: boolean
  confirming?: boolean
  statisticsLoading?: boolean
  canCancel?: boolean
  cancelling?: boolean
  canDelete?: boolean
  deleting?: boolean
  mobile?: boolean
  friends?: readonly Friend[]
}

const props = withDefaults(defineProps<Props>(), {
  detail: null,
  statistics: null,
  loading: false,
  confirming: false,
  statisticsLoading: false,
  canCancel: false,
  cancelling: false,
  canDelete: false,
  deleting: false,
  mobile: false,
  friends: () => [],
})
const emit = defineEmits<{
  confirm: [status: string]
  refreshStats: []
  cancel: []
  delete: []
  complete: [payload: BroadcastCompletePayload]
  remind: [userId: number]
  exportExcel: []
  exportImage: []
  back: []
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
const completionPending = computed(() => Boolean(
  receiver.value
  && receiver.value.confirmStatus !== 'EXECUTED'
  && broadcast.value?.status === 'ACTIVE'
  && !expired.value,
))
const receiptKicker = computed(() => completionPending.value ? '任务进度' : '我的回执')
const receiptTitle = computed(() => {
  if (!completionPending.value) return confirmationLabel(receiver.value?.confirmStatus)
  if (receiver.value?.confirmedAt) return '已收到，等待完成'
  return '等待完成任务'
})
const canCancelCurrent = computed(() => Boolean(
  props.canCancel && broadcast.value?.status === 'ACTIVE',
))
const canDeleteCurrent = computed(() => Boolean(props.canDelete && broadcast.value?.status === 'CANCELLED'))
const confirmationProgress = computed(() => {
  const stats = props.statistics
  if (!stats || stats.targetCount === 0) return 0
  return Math.min(100, Math.max(0, Math.round((stats.confirmedCount / stats.targetCount) * 100)))
})
const selectedBucket = shallowRef('')
const recipients = shallowRef<BroadcastRecipientDetail[]>([])
const recipientLoading = shallowRef(false)
const targetEditorOpen = shallowRef(false)
const targetEditorLoading = shallowRef(false)
const targetSaving = shallowRef(false)
const targetError = shallowRef('')
const currentTargetIds = shallowRef<number[]>([])
const targetIds = shallowRef<number[]>([])
const targetCandidates = shallowRef<BroadcastTargetCandidate[]>([])
const targetRecipients = shallowRef<BroadcastRecipientDetail[]>([])
const lockedTargetIds = computed(() => targetRecipients.value
  .filter((item) => item.targetStatus === 'ACTIVE' && item.confirmStatus === 'EXECUTED')
  .map((item) => item.userId))

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

async function selectBucket(bucket: string): Promise<void> {
  if (!broadcast.value) return
  if (selectedBucket.value === bucket) {
    selectedBucket.value = ''
    recipients.value = []
    return
  }
  selectedBucket.value = bucket
  recipientLoading.value = true
  try {
    recipients.value = await api.broadcasts.recipients(broadcast.value.id, bucket)
  } finally {
    recipientLoading.value = false
  }
}

async function openTargetEditor(): Promise<void> {
  if (!broadcast.value) return
  targetEditorOpen.value = true
  targetEditorLoading.value = true
  targetError.value = ''

  try {
    const recipients = await api.broadcasts.recipients(broadcast.value.id, 'ALL')
    targetRecipients.value = recipients
    currentTargetIds.value = recipients
      .filter((item) => item.targetStatus === 'ACTIVE')
      .map((item) => item.userId)
    targetIds.value = [...currentTargetIds.value]

    const directory = props.canDelete
      ? await api.admin.users().catch(() => [])
      : []
    const candidateMap = new Map<number, BroadcastTargetCandidate>()

    function addCandidate(candidate: BroadcastTargetCandidate): void {
      if (!candidate.userId || !candidate.username) return
      candidateMap.set(candidate.userId, candidate)
    }

    for (const friend of props.friends) {
      addCandidate({
        userId: friend.friendId,
        username: friend.username,
        nickname: friend.remark || friend.nickname,
        avatar: friend.avatar,
      })
    }

    for (const user of directory) {
      if (user.status === 0) continue
      addCandidate({
        userId: user.id,
        username: user.username,
        nickname: user.nickname,
        avatar: user.avatar,
      })
    }

    for (const recipient of recipients) {
      addCandidate({
        userId: recipient.userId,
        username: recipient.username,
        nickname: recipient.nickname,
        avatar: recipient.avatar,
      })
    }

    targetCandidates.value = [...candidateMap.values()].sort((left, right) => {
      const selectedDelta = Number(targetIds.value.includes(right.userId)) - Number(targetIds.value.includes(left.userId))
      return selectedDelta || left.nickname.localeCompare(right.nickname, 'zh-CN')
    })
  } catch (cause) {
    targetError.value = cause instanceof Error ? cause.message : '目标用户加载失败'
  } finally {
    targetEditorLoading.value = false
  }
}

function toggleTarget(userId: number): void {
  if (lockedTargetIds.value.includes(userId)) return
  targetIds.value = targetIds.value.includes(userId)
    ? targetIds.value.filter((id) => id !== userId)
    : [...targetIds.value, userId]
}

async function saveTargets(): Promise<void> {
  if (!broadcast.value) return
  const addUserIds = targetIds.value.filter((id) => !currentTargetIds.value.includes(id))
  const removeUserIds = currentTargetIds.value.filter((id) => !targetIds.value.includes(id))

  if (addUserIds.length === 0 && removeUserIds.length === 0) {
    targetEditorOpen.value = false
    return
  }

  targetSaving.value = true
  targetError.value = ''
  try {
    await api.broadcasts.updateTargets(broadcast.value.id, {
      addUserIds,
      removeUserIds,
    })
    currentTargetIds.value = [...targetIds.value]
    if (selectedBucket.value) {
      recipients.value = await api.broadcasts
        .recipients(broadcast.value.id, selectedBucket.value)
        .catch(() => recipients.value)
    }
    targetEditorOpen.value = false
    emit('refreshStats')
  } catch (cause) {
    targetError.value = cause instanceof Error ? cause.message : '保存目标用户失败'
  } finally {
    targetSaving.value = false
  }
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
        <button
          v-if="mobile"
          class="broadcast-back-button"
          type="button"
          aria-label="返回广播列表"
          @click="emit('back')"
        >
          <UiIcon name="back" :size="20" />
        </button>
        <div class="priority-mark" aria-hidden="true"><UiIcon name="bell" :size="22" /></div>
        <div class="heading-copy">
          <p class="priority-kicker">{{ priorityLabel(broadcast.priority) }}</p>
          <h1 class="broadcast-title">{{ broadcast.title }}</h1>
        </div>
        <div class="document-actions">
          <span v-if="broadcast.status === 'CANCELLED'" class="status-badge status-badge--cancelled">已撤销</span>
          <span v-else-if="broadcast.status === 'COMPLETED'" class="status-badge status-badge--completed">已完成</span>
          <span v-else-if="expired" class="status-badge status-badge--expired">已过期</span>
          <span v-else class="status-badge">进行中</span>
          <button
            v-if="canCancelCurrent"
            class="cancel-button"
            type="button"
            :disabled="cancelling"
            @click="emit('cancel')"
          >
            {{ cancelling ? '撤销中…' : '撤销广播' }}
          </button>
          <button v-if="canDeleteCurrent" class="delete-button" type="button" :disabled="deleting" @click="emit('delete')">
            {{ deleting ? '删除中…' : '永久删除' }}
          </button>
        </div>
      </header>

      <dl class="broadcast-meta">
        <div class="meta-item">
          <template v-if="detail.createdByCurrentUser">
            <dt class="meta-label">接收范围</dt>
            <dd class="meta-value">{{ scopeLabel(broadcast.scopeType) }}</dd>
          </template>
          <template v-else>
            <dt class="meta-label">发送者</dt>
            <dd class="meta-value sender-value">
              <strong>{{ detail.sender.nickname || detail.sender.username }}</strong>
              <small>@{{ detail.sender.username }}</small>
            </dd>
          </template>
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
        <div v-if="detail.contentEvidence?.imageUrls.length" class="content-images">
          <img v-for="url in detail.contentEvidence.imageUrls" :key="url" :src="url" alt="广播附件" />
        </div>
        <p v-if="detail.contentEvidence?.location" class="content-location">
          已附带位置：{{ detail.contentEvidence.location.latitude.toFixed(5) }}, {{ detail.contentEvidence.location.longitude.toFixed(5) }}
        </p>
      </article>

      <section v-if="receiver" class="receipt-panel" :class="{ 'receipt-panel--pending': completionPending }" aria-labelledby="receipt-title">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">{{ receiptKicker }}</p>
            <h2 id="receipt-title" class="panel-title">{{ receiptTitle }}</h2>
          </div>
          <span class="receipt-state" :class="{ 'receipt-state--handled': Boolean(receiver.confirmedAt) || completionPending }">
            <UiIcon :name="receiver.confirmedAt ? 'check' : 'bell'" :size="15" />
          </span>
        </div>

        <p v-if="completionPending && receiver.confirmedAt" class="receipt-note">
          已于 {{ formatDateTime(receiver.confirmedAt) }} 确认收到。完成任务后，本条广播将更新为“已执行”。
        </p>
        <p v-else-if="receiver.confirmedAt" class="receipt-note">
          已于 {{ formatDateTime(receiver.confirmedAt) }} 提交，重复提交相同结果不会重复计数。
        </p>
        <p v-else-if="expired" class="receipt-note receipt-note--warning">确认时限已结束，当前回执不能再更新。</p>
        <p v-else-if="broadcast.confirmationRequired" class="receipt-note">请选择最符合当前处理进度的一项。</p>
        <p v-else class="receipt-note">这条广播不要求确认，打开详情即表示已查看。</p>

        <BroadcastCompletionPanel
          v-if="completionPending"
          :broadcast="broadcast"
          :submitting="confirming"
          @complete="emit('complete', $event)"
        />
        <div v-else-if="broadcast.confirmationRequired" class="confirmation-actions">
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

      <section v-if="detail.createdByCurrentUser || statistics" class="statistics-panel" aria-labelledby="statistics-title">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">实时进度</p>
            <h2 id="statistics-title" class="panel-title">确认统计</h2>
          </div>
          <div class="panel-actions">
            <button
              class="refresh-button refresh-button--icon"
              type="button"
              :disabled="statisticsLoading"
              aria-label="刷新广播统计"
              @click="emit('refreshStats')"
            >
              <UiIcon name="refresh" :size="16" />
            </button>
            <button class="toolbar-button" type="button" aria-label="导出 Excel 明细" @click="emit('exportExcel')">
              导出 Excel
            </button>
            <button class="toolbar-button" type="button" aria-label="导出广播图片" @click="emit('exportImage')">
              导出图片
            </button>
            <button v-if="detail.createdByCurrentUser && broadcast.status === 'ACTIVE'" class="toolbar-button" type="button" @click="openTargetEditor">
              管理通知对象
            </button>
          </div>
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
            <button class="stat-item" type="button" @click="selectBucket('TARGET')"><strong>{{ statistics.targetCount }}</strong><span>目标人数</span></button>
            <button class="stat-item" type="button" @click="selectBucket('DELIVERED')"><strong>{{ statistics.deliveredCount }}</strong><span>已送达</span></button>
            <button class="stat-item" type="button" @click="selectBucket('VIEWED')"><strong>{{ statistics.viewedCount }}</strong><span>已查看</span></button>
            <button class="stat-item" type="button" @click="selectBucket('PENDING')"><strong>{{ statistics.unconfirmedCount }}</strong><span>未执行</span></button>
            <button class="stat-item" type="button" @click="selectBucket('EXECUTED')"><strong>{{ statistics.executedCount }}</strong><span>已执行</span></button>
            <button class="stat-item" type="button" @click="selectBucket('NEED_SUPPORT')"><strong>{{ statistics.needSupportCount }}</strong><span>需支援</span></button>
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

          <div v-if="selectedBucket" class="recipient-section">
            <p class="unconfirmed-label">{{ recipientLoading ? '正在加载成员明细…' : '成员明细' }}</p>
            <button v-for="item in recipients" :key="item.receiverId" class="recipient-row" type="button" :disabled="item.confirmStatus === 'EXECUTED'" @click="emit('remind', item.userId)">
              <UserAvatar :name="item.nickname || item.username" :avatar="item.avatar" :size="32" />
              <span class="recipient-copy"><strong>{{ item.nickname || item.username }}</strong><small>@{{ item.username }}</small></span>
              <em>{{ confirmationLabel(item.confirmStatus) }}</em>
            </button>
            <p v-if="!recipientLoading && recipients.length === 0" class="receipt-note">该分类暂时没有成员。</p>
          </div>

          <BroadcastTargetEditor
            v-if="targetEditorOpen"
            :candidates="targetCandidates"
            :target-ids="targetIds"
            :locked-target-ids="lockedTargetIds"
            :loading="targetEditorLoading"
            :saving="targetSaving"
            :error="targetError"
            @toggle="toggleTarget"
            @cancel="targetEditorOpen = false"
            @save="saveTargets"
          />
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
.broadcast-back-button {
  display: grid;
  width: 38px;
  height: 38px;
  padding: 0;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--separator);
  border-radius: 12px;
  color: var(--ink-soft);
  background: var(--surface);
  cursor: pointer;
}
.broadcast-back-button:hover { color: var(--blue); background: var(--active); }
.broadcast-back-button:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 48%, transparent); outline-offset: 2px; }
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
.document-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.status-badge { padding: 5px 9px; border-radius: 999px; color: var(--green); font-size: 10px; font-weight: 700; background: color-mix(in srgb, var(--green) 10%, transparent); }
.status-badge--expired { color: #d97706; background: color-mix(in srgb, #d97706 10%, transparent); }
.status-badge--cancelled { color: var(--ink-soft); background: var(--fill); }
.status-badge--completed { color: var(--green); background: color-mix(in srgb, var(--green) 12%, var(--fill)); }
.cancel-button { display: inline-flex; min-height: 34px; padding: 0 12px; align-items: center; justify-content: center; border: 0; border-radius: 10px; color: var(--coral); font-size: 12px; font-weight: 680; line-height: 1; white-space: nowrap; background: color-mix(in srgb, var(--coral) 9%, var(--fill)); cursor: pointer; }
.cancel-button:hover:not(:disabled) { background: color-mix(in srgb, var(--coral) 16%, var(--fill)); }
.cancel-button:disabled { cursor: wait; opacity: .55; }
.delete-button { display: inline-flex; min-height: 34px; padding: 0 12px; align-items: center; justify-content: center; border: 0; border-radius: 10px; color: var(--coral); font-size: 12px; font-weight: 680; line-height: 1; white-space: nowrap; background: color-mix(in srgb, var(--coral) 16%, var(--fill)); cursor: pointer; }
.delete-button:disabled { cursor: wait; opacity: .55; }
.sender-value { display: grid; gap: 2px; }
.sender-value small { color: var(--ink-soft); font-size: 11px; }

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
.content-images { display: flex; flex-wrap: wrap; margin-top: 16px; gap: 8px; }
.content-images img { width: min(170px, 100%); height: 120px; border-radius: 11px; object-fit: cover; }
.broadcast-content .content-location { margin-top: 14px; color: var(--ink-soft); font-size: 12px; }

.receipt-panel,
.statistics-panel { margin-top: 14px; padding: 20px; border: 1px solid var(--separator); border-radius: 18px; background: var(--surface-tint); }
.receipt-panel--pending { border-color: color-mix(in srgb, var(--green) 18%, var(--separator)); }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-actions { display: flex; min-width: 0; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
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

.refresh-button--icon,
.toolbar-button { min-height: 36px; border: 1px solid var(--separator); border-radius: 10px; color: var(--ink-soft); font-size: 12px; font-weight: 650; line-height: 1; white-space: nowrap; background: var(--surface); cursor: pointer; }
.refresh-button--icon { display: inline-grid; width: 36px; padding: 0; place-items: center; }
.toolbar-button { display: inline-flex; padding: 0 12px; align-items: center; justify-content: center; }
.refresh-button--icon:hover:not(:disabled),
.toolbar-button:hover:not(:disabled) { color: var(--blue); background: var(--active); }
.refresh-button--icon:disabled,
.toolbar-button:disabled { cursor: wait; opacity: 0.5; }
.statistics-content { margin-top: 17px; }
.progress-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.progress-heading strong { font-size: 26px; letter-spacing: -0.045em; }
.progress-heading span { color: var(--ink-soft); font-size: 11px; }
.progress-track { height: 7px; margin-top: 9px; border-radius: 999px; background: var(--fill); overflow: hidden; }
.progress-value { display: block; height: 100%; border-radius: inherit; background: var(--green); }
.stat-grid { display: grid; margin-top: 17px; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.stat-item { display: grid; min-width: 0; padding: 12px; gap: 3px; border: 0; border-radius: 12px; color: var(--ink); text-align: left; background: var(--surface); cursor: pointer; }
.stat-item:hover { background: var(--hover); }
.stat-item strong { font-size: 18px; letter-spacing: -0.03em; }
.stat-item span { color: var(--ink-faint); font-size: 10px; }
.response-breakdown { display: grid; margin-top: 13px; gap: 1px; border-radius: 12px; background: var(--separator); overflow: hidden; }
.breakdown-row { display: flex; min-height: 38px; padding: 0 12px; align-items: center; justify-content: space-between; background: var(--surface); }
.breakdown-row span { color: var(--ink-soft); font-size: 11px; }
.breakdown-row strong { font-size: 12px; }
.unconfirmed-section { margin-top: 15px; }
.recipient-section { display: grid; margin-top: 15px; gap: 6px; }
.recipient-row { display: flex; min-height: 48px; padding: 7px; align-items: center; gap: 9px; border: 0; border-radius: 10px; color: var(--ink); text-align: left; background: var(--surface); cursor: pointer; }
.recipient-row:disabled { cursor: default; opacity: .66; }
.recipient-row :deep(.avatar) { flex: 0 0 32px; }
.recipient-copy { display: grid; min-width: 0; flex: 1; gap: 1px; }
.recipient-row small, .recipient-row em { color: var(--ink-soft); font-size: 11px; font-style: normal; }
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
  .priority-mark { display: none; }
  .document-actions { flex-direction: column; align-items: flex-end; gap: 5px; }
  .status-badge { white-space: nowrap; }
  .cancel-button { min-height: 38px; padding-inline: 10px; }
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
