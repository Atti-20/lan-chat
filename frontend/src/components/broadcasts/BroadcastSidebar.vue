<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import type { BroadcastPriority, BroadcastStatus, EmergencyBroadcast } from '../../types'
import { formatMessageTime } from '../../utils/format'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  broadcasts: readonly EmergencyBroadcast[]
  selectedId?: number
  loading?: boolean
  canCreate?: boolean
  pendingBroadcastIds?: readonly number[]
}

const props = withDefaults(defineProps<Props>(), {
  selectedId: undefined,
  loading: false,
  canCreate: false,
  pendingBroadcastIds: () => [],
})
const emit = defineEmits<{
  select: [broadcast: EmergencyBroadcast]
  create: []
}>()

type PriorityFilter = 'ALL' | BroadcastPriority
type StatusFilter = BroadcastStatus | 'ALL'

const query = shallowRef('')
const priorityFilter = shallowRef<PriorityFilter>('ALL')
const statusFilter = shallowRef<StatusFilter>('ACTIVE')
const statusFilters: readonly { value: StatusFilter; label: string }[] = [
  { value: 'ACTIVE', label: '进行中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已撤销' },
  { value: 'ALL', label: '全部' },
]
const filters: readonly { value: PriorityFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'EMERGENCY', label: '紧急' },
  { value: 'IMPORTANT', label: '重要' },
  { value: 'NORMAL', label: '普通' },
]

const pendingIds = computed(() => new Set(props.pendingBroadcastIds))
const visibleBroadcasts = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase('zh-CN')
  return [...props.broadcasts]
    .filter((broadcast) => statusFilter.value === 'ALL' || broadcast.status === statusFilter.value)
    .filter((broadcast) => priorityFilter.value === 'ALL' || broadcast.priority === priorityFilter.value)
    .filter((broadcast) => !needle
      || broadcast.title.toLocaleLowerCase('zh-CN').includes(needle)
      || broadcast.content.toLocaleLowerCase('zh-CN').includes(needle))
    .sort((left, right) => timestamp(right.createTime) - timestamp(left.createTime))
})

const emptyCopy = computed(() => {
  if (query.value.trim()) return '没有匹配的广播'
  if (statusFilter.value === 'ACTIVE') return '当前没有进行中的广播'
  if (statusFilter.value === 'COMPLETED') return '还没有已完成的广播'
  if (statusFilter.value === 'CANCELLED') return '还没有已撤销的广播'
  if (priorityFilter.value !== 'ALL') return '当前级别还没有广播'
  return '广播发布后会出现在这里'
})

function timestamp(value?: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function priorityLabel(priority: BroadcastPriority): string {
  return {
    NORMAL: '普通',
    IMPORTANT: '重要',
    EMERGENCY: '紧急',
  }[priority]
}

function scopeLabel(broadcast: EmergencyBroadcast): string {
  if (broadcast.scopeType === 'ALL') return '全体成员'
  if (broadcast.scopeType === 'GROUP') return '群组范围'
  return '指定成员'
}
</script>

<template>
  <aside class="broadcast-sidebar" aria-label="应急广播列表">
    <header class="sidebar-header">
      <div class="header-copy">
        <p class="header-kicker">应急协作</p>
        <h1 class="header-title">广播</h1>
      </div>
      <button
        v-if="canCreate"
        class="create-button"
        type="button"
        aria-label="创建广播"
        @click="emit('create')"
      >
        <UiIcon name="plus" :size="18" />
      </button>
    </header>

    <label class="sidebar-search">
      <span class="sr-only">搜索广播</span>
      <UiIcon name="search" :size="17" />
      <input v-model="query" type="search" placeholder="搜索标题或内容" />
    </label>

    <div class="status-filters" role="group" aria-label="按广播状态筛选">
      <button
        v-for="filter in statusFilters"
        :key="filter.value"
        class="filter-button"
        :class="{ 'filter-button--active': statusFilter === filter.value }"
        type="button"
        :aria-pressed="statusFilter === filter.value"
        @click="statusFilter = filter.value"
      >{{ filter.label }}</button>
    </div>

    <div class="priority-filters" role="group" aria-label="按优先级筛选">
      <button
        v-for="filter in filters"
        :key="filter.value"
        class="filter-button"
        :class="{ 'filter-button--active': priorityFilter === filter.value }"
        type="button"
        :aria-pressed="priorityFilter === filter.value"
        @click="priorityFilter = filter.value"
      >
        {{ filter.label }}
      </button>
    </div>

    <div v-if="loading" class="broadcast-loading" aria-label="正在载入广播">
      <span v-for="index in 5" :key="index" class="loading-row" />
    </div>

    <div v-else class="broadcast-list">
      <button
        v-for="broadcast in visibleBroadcasts"
        :key="broadcast.id"
        class="broadcast-item"
        :class="{
          'broadcast-item--active': selectedId === broadcast.id,
          'broadcast-item--pending': pendingIds.has(broadcast.id),
          'broadcast-item--cancelled': broadcast.status === 'CANCELLED',
        }"
        :data-priority="broadcast.priority"
        type="button"
        @click="emit('select', broadcast)"
      >
        <span class="priority-rail" aria-hidden="true" />
        <span class="item-copy">
          <span class="item-heading">
            <strong class="item-title">{{ broadcast.title }}</strong>
            <time class="item-time">{{ formatMessageTime(broadcast.createTime) }}</time>
          </span>
          <span class="item-preview">{{ broadcast.content }}</span>
          <span class="item-meta">
            <span class="priority-badge">{{ priorityLabel(broadcast.priority) }}</span>
            <span>{{ scopeLabel(broadcast) }}</span>
            <span v-if="broadcast.confirmationRequired">需确认</span>
            <span v-if="pendingIds.has(broadcast.id)" class="pending-badge">待处理</span>
            <span v-else-if="broadcast.status === 'COMPLETED'">已完成</span>
            <span v-else-if="broadcast.status === 'CANCELLED'">已撤销</span>
          </span>
        </span>
      </button>

      <div v-if="visibleBroadcasts.length === 0" class="empty-state">
        <span class="empty-icon"><UiIcon name="bell" :size="24" /></span>
        <strong>{{ emptyCopy }}</strong>
        <p>{{ canCreate ? '使用右上角按钮发布第一条通知。' : '收到的新广播会优先显示。' }}</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.broadcast-sidebar {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  border-right: 1px solid var(--separator);
  color: var(--ink);
  background: var(--surface-raise);
}

.sidebar-header {
  display: flex;
  padding: 22px 18px 13px;
  align-items: center;
  justify-content: space-between;
}

.header-kicker {
  margin: 0 0 3px;
  color: var(--coral);
  font-size: 11px;
  font-weight: 650;
}

.header-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.04em;
}

.create-button {
  display: inline-flex;
  min-width: 34px;
  height: 34px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  color: var(--coral);
  background: color-mix(in srgb, var(--coral) 10%, var(--fill));
  cursor: pointer;
  transition: background-color 160ms ease, transform 160ms var(--ease-liquid);
}

.create-button:hover { background: color-mix(in srgb, var(--coral) 17%, var(--fill)); }
.create-button:active { transform: scale(0.96); }

.sidebar-search {
  display: flex;
  min-height: 40px;
  margin: 0 12px 10px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border-radius: 11px;
  color: var(--ink-faint);
  background: var(--fill);
}

.sidebar-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  color: var(--ink);
  background: transparent;
  outline: none;
}

.sidebar-search input::placeholder { color: var(--ink-faint); }

.status-filters,
.priority-filters {
  display: grid;
  margin: 0 12px 10px;
  padding: 3px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-radius: 11px;
  background: var(--fill);
}

.filter-button {
  min-width: 0;
  min-height: 30px;
  padding: 0 6px;
  border: 0;
  border-radius: 10px;
  color: var(--ink-soft);
  font-size: 11px;
  font-weight: 600;
  background: transparent;
  cursor: pointer;
}

.filter-button--active {
  color: var(--ink);
  background: var(--surface-raise);
  box-shadow: 0 1px 5px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}

.broadcast-list,
.broadcast-loading {
  min-height: 0;
  padding: 0 8px 14px;
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--separator-strong) transparent;
}

.broadcast-list {
  display: flex;
  flex-direction: column;
}

.broadcast-loading { display: grid; align-content: start; gap: 6px; }

.loading-row {
  height: 86px;
  border-radius: 13px;
  background: var(--fill);
  animation: loading-pulse 1.4s ease-in-out infinite alternate;
}

.broadcast-item {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 88px;
  padding: 11px 10px 11px 15px;
  align-items: stretch;
  border: 0;
  border-radius: 13px;
  color: var(--ink);
  text-align: left;
  background: transparent;
  cursor: pointer;
  overflow: hidden;
  transition: background-color 150ms ease;
}

.broadcast-item:hover { background: var(--hover); }
.broadcast-item--active { background: var(--active); }
.broadcast-item--cancelled { opacity: 0.58; }

.priority-rail {
  position: absolute;
  top: 15px;
  bottom: 15px;
  left: 7px;
  width: 3px;
  border-radius: 3px;
  background: var(--blue);
}

.broadcast-item[data-priority="IMPORTANT"] .priority-rail { background: #d97706; }
.broadcast-item[data-priority="EMERGENCY"] .priority-rail { background: var(--coral); }

.item-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 6px;
}

.item-heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.item-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 14px;
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-time {
  flex: 0 0 auto;
  color: var(--ink-faint);
  font-size: 10px;
}

.item-preview {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.item-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: var(--ink-faint);
  font-size: 10px;
  white-space: nowrap;
}

.priority-badge,
.pending-badge {
  padding: 2px 6px;
  border-radius: 999px;
  color: var(--ink-soft);
  background: var(--fill);
}

.broadcast-item[data-priority="EMERGENCY"] .priority-badge {
  color: var(--coral);
  background: color-mix(in srgb, var(--coral) 10%, transparent);
}

.pending-badge {
  margin-left: auto;
  color: var(--blue);
  background: var(--active);
}

.empty-state {
  display: grid;
  min-height: 250px;
  flex: 1;
  padding: 42px 24px;
  align-content: center;
  place-content: center;
  place-items: center;
  text-align: center;
}

.empty-icon {
  display: grid;
  width: 54px;
  height: 54px;
  margin-bottom: 13px;
  place-items: center;
  border-radius: 50%;
  color: var(--coral);
  background: color-mix(in srgb, var(--coral) 9%, var(--fill));
}

.empty-state strong { font-size: 14px; }
.empty-state p { max-width: 220px; margin: 7px 0 0; color: var(--ink-soft); font-size: 12px; line-height: 1.55; }

@keyframes loading-pulse {
  from { opacity: 0.55; }
  to { opacity: 1; }
}

@media (max-width: 760px) {
  .broadcast-sidebar {
    padding-bottom: calc(82px + env(safe-area-inset-bottom));
    border-right: 0;
    background: var(--surface);
  }

  .sidebar-header {
    padding: max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) 12px max(16px, env(safe-area-inset-left));
  }

  .create-button { width: 42px; height: 42px; }
  .sidebar-search { min-height: 44px; }
  .broadcast-item { min-height: 92px; }
}

@media (prefers-reduced-motion: reduce) {
  .loading-row { animation: none; }
}
</style>
