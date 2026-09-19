<script setup lang="ts">
defineProps<{ loading: boolean }>()
const action = defineModel<string>('action', { required: true })
const outcome = defineModel<string>('outcome', { required: true })
defineEmits<{ refresh: [] }>()
</script>

<template>
  <header class="audit-toolbar">
    <div><h2 class="audit-title">操作审计</h2><p class="audit-description">查看账号、权限和设备管理记录，每次最多显示最近 100 条。</p></div>
    <form class="audit-filters" @submit.prevent="$emit('refresh')">
      <label class="audit-field">操作类型<input v-model="action" class="field" placeholder="全部，或填写操作代码" maxlength="100"></label>
      <label class="audit-field">操作结果<select v-model="outcome" class="field"><option value="">全部</option><option value="SUCCEEDED">成功</option><option value="DENIED">拒绝</option></select></label>
      <button class="primary-button" type="submit" :disabled="loading">{{ loading ? '加载中…' : '查询 / 刷新' }}</button>
    </form>
  </header>
</template>

<style scoped>
.audit-toolbar { min-width: 0; padding: var(--space-6); border-bottom: 1px solid var(--separator); background: var(--surface-raise); }
.audit-title { margin: 0 0 var(--space-2); font-size: var(--font-title); }
.audit-description { margin: 0; color: var(--ink-soft); font-size: var(--font-body-sm); }
.audit-filters { display: grid; grid-template-columns: minmax(0, 1fr) minmax(100px, .55fr) auto; gap: var(--space-3); align-items: end; margin-top: var(--space-5); }
.audit-field { display: grid; min-width: 0; gap: var(--space-2); font-size: var(--font-caption); color: var(--ink-soft); }
@media (max-width: 1100px) {
  .audit-filters { grid-template-columns: minmax(0, 1fr) minmax(100px, .55fr); }
  .audit-filters > button { grid-column: 1 / -1; }
}
@media (max-width: 760px) {
  .audit-toolbar { padding: var(--space-4); }
}
</style>
