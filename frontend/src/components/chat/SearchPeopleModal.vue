<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { api } from '../../services/api'
import type { User } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'

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
  <div v-if="open" class="modal-backdrop apple-modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="search-sheet apple-modal-surface" role="dialog" aria-modal="true" aria-labelledby="people-title">
      <button class="close-button apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <h2 id="people-title">查找好友</h2>

      <label class="search-field">
        <UiIcon name="search" :size="18" />
        <input v-model="query" autofocus placeholder="输入用户名或昵称" />
      </label>

      <label class="message-field">
        <span>申请留言</span>
        <input v-model="message" maxlength="20" />
      </label>

      <div class="result-list">
        <p v-if="loading" class="result-state">正在搜索…</p>
        <div v-for="user in results" v-else :key="user.id" class="result-item">
          <UserAvatar :name="user.nickname" :avatar="user.avatar" :size="40" :online="user.online === 1" />
          <div class="result-info">
            <strong>{{ user.nickname }}</strong>
            <small>@{{ user.username }}</small>
          </div>
          <button
            type="button"
            :disabled="friendIds.includes(user.id)"
            @click="emit('request', user.id, message.trim())"
          >{{ friendIds.includes(user.id) ? '已是好友' : '添加' }}</button>
        </div>
        <p v-if="!loading && query && results.length === 0" class="result-state">没有找到匹配的用户</p>
        <p v-if="!query" class="result-state">输入至少一个字符开始查找</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 100;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.search-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  max-height: min(680px, 90dvh);
  padding: 28px 24px 20px;
  gap: 14px;
  border-radius: 22px;
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow-y: auto;
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.close-button {
  position: absolute;
  top: 14px;
  right: 14px;
  display: grid;
  width: 34px;
  height: 34px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
  transition: background-color 150ms ease;
}
.close-button:hover { background: var(--button-hover); }
.close-button .ui-icon { width: 16px; }

.search-sheet h2 { margin: 0; font-size: 20px; letter-spacing: -0.02em; }

.search-field {
  display: flex;
  height: 44px;
  padding: 0 14px;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 12px;
  background: var(--fill);
}
.search-field .ui-icon { width: 18px; flex-shrink: 0; color: var(--ink-faint); }
.search-field input { width: 100%; border: 0; font-size: 14px; outline: none; background: none; }

.message-field { display: grid; gap: 6px; }
.message-field span { color: var(--ink-soft); font-size: 11px; font-weight: 600; }
.message-field input {
  height: 40px;
  padding: 0 13px;
  border: 0;
  border-radius: 12px;
  font-size: 13px;
  outline: none;
  background: var(--fill);
}

.result-list { display: grid; min-height: 120px; gap: 2px; }

.result-item {
  display: flex;
  padding: 10px;
  align-items: center;
  gap: 12px;
  border-radius: 13px;
  transition: background-color 150ms ease;
}
.result-item:hover { background: var(--hover); }

.result-info {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 2px;
}
.result-info strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-info small {
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-item > button {
  min-width: 56px;
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  background: var(--blue);
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity 150ms ease;
}
.result-item > button:hover { opacity: 0.85; }
.result-item > button:disabled {
  color: var(--ink-faint);
  background: var(--hover-strong);
  cursor: default;
  opacity: 1;
}

.result-state {
  padding: 32px 0;
  color: var(--ink-faint);
  font-size: 12px;
  text-align: center;
}
</style>
