<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { api } from '../../services/api'
import type { User } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  open: boolean
  currentUserId: number
  friendIds: readonly number[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  request: [userId: number, message: string]
}>()
const query = shallowRef('')
const results = ref<User[]>([])
const loading = shallowRef(false)
const message = shallowRef('你好，想和你成为好友')
let searchSequence = 0

watch(query, (value, _previous, onCleanup) => {
  const sequence = ++searchSequence
  const clean = value.trim()
  if (!clean) {
    results.value = []
    return
  }
  const timer = window.setTimeout(async () => {
    loading.value = true
    try {
      const users = await api.user.search(clean)
      if (sequence === searchSequence) results.value = users.filter((user) => user.id !== props.currentUserId)
    } finally {
      if (sequence === searchSequence) loading.value = false
    }
  }, 280)
  onCleanup(() => window.clearTimeout(timer))
})

watch(() => props.open, (open) => {
  if (!open) {
    query.value = ''
    results.value = []
  }
})
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="modal-sheet glass-surface" role="dialog" aria-modal="true" aria-labelledby="people-title">
      <header>
        <div><h2 id="people-title">查找好友</h2></div>
        <button type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>
      <label class="search-field">
        <svg viewBox="0 0 24 24" fill="none"><circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8"/><path d="m16.2 16.2 4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        <input v-model="query" autofocus placeholder="输入用户名或昵称" />
      </label>
      <label class="message-field">
        <span>申请留言</span>
        <input v-model="message" maxlength="20" />
      </label>
      <div class="result-list">
        <p v-if="loading" class="result-state">正在搜索…</p>
        <article v-for="user in results" v-else :key="user.id">
          <UserAvatar :name="user.nickname" :avatar="user.avatar" :size="46" :online="user.online === 1" />
          <span><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></span>
          <button
            type="button"
            :disabled="friendIds.includes(user.id)"
            @click="emit('request', user.id, message.trim())"
          >
            {{ friendIds.includes(user.id) ? '已是好友' : '添加' }}
          </button>
        </article>
        <p v-if="!loading && query && results.length === 0" class="result-state">没有找到匹配的用户</p>
        <p v-if="!query" class="result-state">输入至少一个字符开始查找</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; padding: 20px; place-items: center; background: rgba(39,64,92,.18); backdrop-filter: blur(10px); }
.modal-sheet { width: min(100%, 480px); max-height: min(700px, 90dvh); padding: 24px; overflow-y: auto; border-radius: 30px 30px 30px 15px; }
.modal-sheet header { display: flex; align-items: start; justify-content: space-between; }
.modal-sheet header p { margin: 0 0 4px; color: #4f80ad; font-family: "SF Mono", monospace; font-size: 9px; font-weight: 700; letter-spacing: .14em; }
.modal-sheet h2 { margin: 0; font-size: 25px; letter-spacing: -.04em; }
.modal-sheet header button { width: 36px; height: 36px; border: 1px solid rgba(255,255,255,.72); border-radius: 13px; color: #60748a; font-size: 22px; background: rgba(255,255,255,.45); cursor: pointer; }
.search-field { display: flex; min-height: 48px; padding: 0 14px; margin-top: 22px; align-items: center; gap: 9px; border: 1px solid rgba(133,163,194,.22); border-radius: 16px; background: rgba(255,255,255,.56); }
.search-field svg { width: 19px; color: #71869a; }
.search-field input { width: 100%; border: 0; outline: none; background: none; }
.message-field { display: grid; margin-top: 15px; gap: 7px; }
.message-field span { color: #526c85; font-size: 11px; font-weight: 700; }
.message-field input { min-height: 42px; padding: 0 13px; border: 1px solid rgba(133,163,194,.18); border-radius: 14px; outline: none; background: rgba(255,255,255,.4); }
.result-list { display: grid; min-height: 160px; margin-top: 15px; gap: 7px; }
.result-list article { display: flex; padding: 9px; align-items: center; gap: 11px; border-radius: 17px; }
.result-list article:hover { background: rgba(255,255,255,.38); }
.result-list article > span { display: grid; min-width: 0; flex: 1; gap: 2px; }
.result-list strong { font-size: 13px; }.result-list small { color: var(--ink-soft); font-size: 10px; }
.result-list article > button { min-height: 34px; padding: 0 13px; border: 0; border-radius: 11px; color: white; font-size: 10px; font-weight: 700; background: var(--blue); cursor: pointer; }
.result-list article > button:disabled { color: #7a8ca0; background: rgba(206,220,234,.72); cursor: default; }
.result-state { margin: 40px 0; color: var(--ink-soft); font-size: 12px; text-align: center; }

.modal-backdrop { background: rgba(28, 28, 30, 0.18); backdrop-filter: blur(7px); }
.modal-sheet {
  padding: 24px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 20px 60px rgba(29, 29, 31, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.96);
}
.modal-sheet h2 { font-size: 23px; }
.modal-sheet header button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
}
.search-field,
.message-field input {
  border: 0;
  border-radius: 12px;
  background: var(--fill);
}
.message-field span { color: var(--ink-soft); font-weight: 600; }
.result-list article { border-radius: 12px; }
.result-list article:hover { background: rgba(118, 118, 128, 0.07); }
.result-list article > button { border-radius: 10px; background: var(--blue); }
</style>
