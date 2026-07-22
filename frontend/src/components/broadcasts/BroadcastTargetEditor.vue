<script setup lang="ts">
import { computed } from 'vue'
import type { BroadcastTargetCandidate } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  candidates: readonly BroadcastTargetCandidate[]
  targetIds: readonly number[]
  lockedTargetIds?: readonly number[]
  loading?: boolean
  saving?: boolean
  error?: string
}

const props = withDefaults(defineProps<Props>(), {
  lockedTargetIds: () => [],
  loading: false,
  saving: false,
  error: '',
})

const emit = defineEmits<{
  toggle: [userId: number]
  cancel: []
  save: []
}>()

const selectedIds = computed(() => new Set(props.targetIds))
const lockedIds = computed(() => new Set(props.lockedTargetIds))

function isSelected(userId: number): boolean {
  return selectedIds.value.has(userId)
}

function isLocked(userId: number): boolean {
  return lockedIds.value.has(userId)
}

function actionLabel(userId: number): string {
  if (isLocked(userId)) return '已执行'
  return isSelected(userId) ? '移出目标' : '加入目标'
}
</script>

<template>
  <section class="target-editor" aria-label="管理通知对象">
    <div class="target-editor-heading">
      <div>
        <p class="target-editor-title">管理通知对象</p>
        <p class="target-editor-copy">点击成员即可加入或移出通知对象；已执行成员不能移出。</p>
      </div>
      <strong class="target-count">已选 {{ targetIds.length }} 人</strong>
    </div>

    <p v-if="loading" class="target-empty">正在加载可选成员…</p>

    <template v-else>
      <button
        v-for="candidate in candidates"
        :key="candidate.userId"
        class="target-row apple-list-row"
        :class="{ 'target-row--selected': isSelected(candidate.userId) }"
        type="button"
        :disabled="saving || isLocked(candidate.userId)"
        @click="emit('toggle', candidate.userId)"
      >
        <UserAvatar :name="candidate.nickname || candidate.username" :avatar="candidate.avatar" :size="30" />
        <span class="target-copy">
          <strong>{{ candidate.nickname || candidate.username }}</strong>
          <small>@{{ candidate.username }}</small>
        </span>
        <span class="target-action">{{ actionLabel(candidate.userId) }}</span>
      </button>

      <p v-if="candidates.length === 0" class="target-empty">暂无可管理的成员。</p>
    </template>

    <p v-if="error" class="target-error">{{ error }}</p>

    <div class="target-actions">
      <button class="secondary-button" type="button" :disabled="saving" @click="emit('cancel')">取消</button>
      <button class="primary-button" type="button" :disabled="loading || saving" @click="emit('save')">
        {{ saving ? '保存中…' : '保存目标' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.target-editor { display: grid; margin-top: 15px; padding: 14px; gap: 7px; border: 1px solid var(--separator); border-radius: 12px; background: var(--surface); }
.target-editor-heading { display: flex; margin-bottom: 3px; align-items: flex-start; justify-content: space-between; gap: 12px; }
.target-editor-title { margin: 0; color: var(--ink); font-size: 13px; font-weight: 700; }
.target-editor-copy { margin: 4px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.45; }
.target-count { flex: 0 0 auto; color: var(--blue); font-size: 11px; }
.target-row { display: flex; min-height: 46px; padding: 7px 8px; align-items: center; gap: 9px; border: 0; border-radius: 10px; color: var(--ink); text-align: left; background: transparent; cursor: pointer; }
.target-row:hover:not(:disabled) { background: var(--hover); }
.target-row--selected { background: var(--active); }
.target-row:disabled { cursor: not-allowed; opacity: .62; }
.target-row :deep(.avatar) { flex: 0 0 30px; }
.target-copy { display: grid; min-width: 0; flex: 1; gap: 1px; }
.target-copy strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.target-copy small { overflow: hidden; color: var(--ink-soft); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.target-action { flex: 0 0 auto; color: var(--blue); font-size: 11px; font-weight: 650; }
.target-row--selected .target-action { color: var(--coral); }
.target-row:disabled .target-action { color: var(--ink-soft); }
.target-empty { margin: 12px 0; color: var(--ink-soft); font-size: 12px; text-align: center; }
.target-error { margin: 4px 0 0; color: var(--coral); font-size: 11px; line-height: 1.5; }
.target-actions { display: flex; margin-top: 5px; justify-content: flex-end; gap: 7px; }

@media (max-width: 760px) {
  .target-editor-heading { align-items: flex-start; }
  .target-actions .secondary-button,
  .target-actions .primary-button { flex: 1; }
}
</style>
