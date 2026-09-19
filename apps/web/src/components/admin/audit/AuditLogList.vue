<script setup lang="ts">
import type { AuditEvent } from '../../../types'
defineProps<{ events: readonly AuditEvent[]; loading: boolean; error: string }>()
function readableDetail(value?: string): string {
  if (!value) return '无附加信息'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
</script>

<template>
  <div class="audit-list" :aria-busy="loading">
    <p v-if="error" class="audit-error" role="alert">{{ error }}</p>
    <p v-else-if="loading" role="status">正在读取操作记录…</p>
    <p v-else-if="!events.length" class="audit-empty">没有符合条件的操作记录。</p>
    <article v-for="event in events" v-else :key="event.id" class="audit-event">
      <div class="event-summary"><strong>{{ event.action }}</strong><span class="outcome" :class="{ denied: event.outcome === 'DENIED' }">{{ event.outcome === 'SUCCEEDED' ? '成功' : event.outcome === 'DENIED' ? '拒绝' : event.outcome }}</span></div>
      <p class="event-meta"><time>{{ event.createdAt.replace('T', ' ') }}</time><span>操作者：{{ event.actorUserId ?? '系统' }}</span><span>对象：{{ event.targetType || '—' }} {{ event.targetId || '' }}</span></p>
      <details><summary>查看详情</summary><p>请求标识：{{ event.requestId || '—' }}</p><pre>{{ readableDetail(event.detailJson) }}</pre></details>
    </article>
  </div>
</template>

<style scoped>
.audit-list { padding: var(--space-2) var(--space-6) var(--space-6); overflow: auto; min-width: 0; min-height: 0; }
.audit-error { color: var(--danger); }
.audit-empty { color: var(--ink-soft); padding-top: 16px; }
.audit-event { min-width: 0; padding: var(--space-5) 0; border-bottom: 1px solid var(--separator); overflow-wrap: anywhere; }
.event-summary { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); overflow-wrap: anywhere; }
.event-meta { display: flex; flex-wrap: wrap; gap: 8px 18px; color: var(--ink-soft); font-size: var(--font-caption); }
.outcome { color: var(--success); flex-shrink: 0; font-size: var(--font-caption); }
.outcome.denied { color: var(--danger); }
details { font-size: var(--font-caption); color: var(--ink-soft); }
summary { cursor: pointer; width: fit-content; padding: var(--space-2) 0; }
pre { overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; padding: var(--space-3); border-radius: var(--radius-sm); background: var(--fill); color: var(--ink); }
@media (max-width: 760px) {
  .audit-list { padding: var(--space-2) var(--space-4) var(--space-4); }
}
</style>
