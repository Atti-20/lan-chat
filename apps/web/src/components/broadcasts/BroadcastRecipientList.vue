<script setup lang="ts">
import type { BroadcastRecipientDetail } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import BroadcastEvidenceImage from './BroadcastEvidenceImage.vue'
defineProps<{ recipients: readonly BroadcastRecipientDetail[]; loading: boolean; error: string; canRemind: boolean }>()
defineEmits<{ remind: [userId: number] }>()
function statusLabel(status: string): string {
  return ({ EXECUTED: '已执行', NEED_SUPPORT: '需支援', PENDING: '等待确认', DELIVERED: '已送达', VIEWED: '已查看', RECEIVED: '已收到', EXPIRED: '已过期', NOT_REQUIRED: '无需确认' } as Record<string, string>)[status] || status
}
function timeLabel(value?: string): string { return value?.replace('T', ' ') || '尚未记录' }
</script>

<template>
  <section class="recipient-section" aria-label="成员回执明细" :aria-busy="loading">
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="loading" role="status">正在加载成员明细…</p>
    <template v-else>
      <p v-if="!recipients.length">该分类暂时没有成员。</p>
      <article v-for="item in recipients" :key="item.receiverId" class="recipient-card">
        <header>
          <UserAvatar :name="item.nickname || item.username" :avatar="item.avatar" :size="32" />
          <span class="recipient-copy"><strong>{{ item.nickname || item.username }}</strong><small>@{{ item.username }}</small></span>
          <span class="receipt-status">{{ statusLabel(item.confirmStatus) }}</span>
          <button v-if="canRemind && item.targetStatus === 'ACTIVE' && item.confirmStatus !== 'EXECUTED' && item.confirmStatus !== 'NOT_REQUIRED'" type="button" @click="$emit('remind', item.userId)">提醒</button>
        </header>
        <details>
          <summary>查看回执详情<span v-if="item.imageUrls.length"> · {{ item.imageUrls.length }} 张图片</span></summary>
          <dl><dt>送达</dt><dd>{{ timeLabel(item.deliveredAt) }}</dd><dt>查看</dt><dd>{{ timeLabel(item.viewedAt) }}</dd><dt>完成</dt><dd>{{ timeLabel(item.completedAt) }}</dd><dt>提醒</dt><dd>{{ item.remindCount }} 次</dd></dl>
          <div v-if="item.imageUrls.length" class="evidence-images">
            <BroadcastEvidenceImage v-for="(url, index) in item.imageUrls" :key="url" :source="url" :alt="`${item.nickname || item.username}的回执图片 ${index + 1}`" />
          </div>
          <p v-if="item.location">定位：{{ item.location.latitude }}, {{ item.location.longitude }}<span v-if="item.location.accuracyMeters != null">（精度约 {{ Math.round(item.location.accuracyMeters) }} 米）</span></p>
        </details>
      </article>
    </template>
  </section>
</template>

<style scoped>
.recipient-section { display: grid; margin-top: 15px; gap: 10px; }
.recipient-card { padding: var(--space-3); border: 1px solid var(--separator); border-radius: var(--radius-control); background: var(--surface); }
header { display: flex; align-items: center; gap: 9px; }
.recipient-copy { display: grid; min-width: 0; flex: 1; gap: 2px; overflow-wrap: anywhere; }
small, .receipt-status, details, p { color: var(--ink-soft); font-size: var(--font-caption); }
button { padding: 6px 10px; border: 1px solid var(--separator); border-radius: var(--radius-sm); background: var(--surface); color: var(--accent-text); cursor: pointer; }
details { margin-top: 12px; }
summary { cursor: pointer; }
dl { display: grid; grid-template-columns: auto 1fr; gap: 6px 16px; }
dd { margin: 0; }
.evidence-images { display: flex; gap: 10px; flex-wrap: wrap; }
.evidence-images :deep(img) { max-width: min(100%, 320px); max-height: 240px; object-fit: contain; border: 1px solid var(--separator); border-radius: var(--radius-sm); }
</style>
