<script setup lang="ts">
import type { ChatMessage, MentionReadReceipt } from '../../types'
import UiIcon from '../base/UiIcon.vue'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  open: boolean
  message?: ChatMessage | null
  receipt?: MentionReadReceipt | null
  loading?: boolean
  error?: string
}

withDefaults(defineProps<Props>(), {
  message: null,
  receipt: null,
  loading: false,
  error: '',
})
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div v-if="open" class="mention-receipt-backdrop" role="presentation" @click.self="emit('close')">
    <section class="mention-receipt-sheet" role="dialog" aria-modal="true" aria-labelledby="mention-receipt-title">
      <header>
        <div>
          <p>群聊 @ 成员</p>
          <h2 id="mention-receipt-title">接受详情</h2>
          <span v-if="receipt">{{ receipt.readCount }} / {{ receipt.expectedCount }} 人已读</span>
        </div>
        <button type="button" aria-label="关闭接受详情" @click="emit('close')"><UiIcon name="close" :size="18" /></button>
      </header>

      <p v-if="message?.content" class="message-preview">{{ message.content }}</p>
      <div v-if="loading" class="receipt-loading"><span /><span /><span /></div>
      <p v-else-if="error" class="receipt-error">{{ error }}</p>
      <ol v-else-if="receipt" class="receipt-list">
        <li v-for="recipient in receipt.recipients" :key="recipient.userId">
          <UserAvatar :name="recipient.nickname" :avatar="recipient.avatar" :size="38" />
          <span class="recipient-copy"><strong>{{ recipient.nickname }}</strong></span>
          <span class="recipient-state" :class="{ 'recipient-state--read': recipient.read }">
            <UiIcon :name="recipient.read ? 'check' : 'bell'" :size="14" />
            {{ recipient.read ? '已读' : '未读' }}
          </span>
        </li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.mention-receipt-backdrop { position: fixed; z-index: 190; inset: 0; display: grid; padding: max(18px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) max(18px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left)); place-items: end center; background: color-mix(in srgb, var(--backdrop) 88%, transparent); backdrop-filter: blur(14px); }
.mention-receipt-sheet { width: min(100%, 460px); max-height: min(620px, calc(100dvh - 36px)); border: 1px solid var(--separator); border-radius: 22px; color: var(--ink); background: var(--surface); box-shadow: 0 24px 64px var(--shadow-color); overflow: hidden; }
.mention-receipt-sheet header { display: flex; min-height: 72px; padding: 16px 16px 12px; align-items: start; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--separator); }
.mention-receipt-sheet header div { display: grid; gap: 2px; }
.mention-receipt-sheet header p { margin: 0; color: var(--accent-text); font-size: var(--font-micro); font-weight: 750; letter-spacing: .08em; }
.mention-receipt-sheet h2 { margin: 0; font-size: var(--font-title); letter-spacing: -.025em; }
.mention-receipt-sheet header span { color: var(--ink-faint); font-size: var(--font-caption); }
.mention-receipt-sheet header button { display: grid; width: 44px; height: 44px; padding: 0; place-items: center; border: 0; border-radius: 50%; color: var(--ink-soft); background: var(--fill); cursor: pointer; }
.message-preview { display: -webkit-box; margin: 12px 16px 0; overflow: hidden; color: var(--ink-soft); font-size: var(--font-caption); line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.receipt-list { display: grid; max-height: 430px; padding: 8px 16px 16px; margin: 0; overflow-y: auto; list-style: none; }
.receipt-list li { display: flex; min-height: 58px; align-items: center; gap: 10px; border-bottom: 1px solid var(--separator); }
.receipt-list li:last-child { border-bottom: 0; }
.recipient-copy { display: grid; min-width: 0; flex: 1; gap: 2px; }
.recipient-copy strong { overflow: hidden; font-size: var(--font-body); text-overflow: ellipsis; white-space: nowrap; }
.recipient-copy small { color: var(--ink-faint); font-size: var(--font-micro); }
.recipient-state { display: inline-flex; min-width: 48px; align-items: center; justify-content: flex-end; gap: 3px; color: var(--ink-faint); font-size: var(--font-caption); }
.recipient-state--read { color: var(--success); font-weight: 700; }
.receipt-loading { display: flex; min-height: 140px; align-items: center; justify-content: center; gap: 5px; }
.receipt-loading span { width: 7px; height: 7px; border-radius: 50%; background: var(--accent-text); animation: receipt-bounce .8s ease-in-out infinite alternate; }
.receipt-loading span:nth-child(2) { animation-delay: .12s; }.receipt-loading span:nth-child(3) { animation-delay: .24s; }
.receipt-error { padding: 22px 16px; margin: 0; color: var(--danger); font-size: var(--font-caption); }
@keyframes receipt-bounce { to { transform: translateY(-5px); opacity: .45; } }
</style>
