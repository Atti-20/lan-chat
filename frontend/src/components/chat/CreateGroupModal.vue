<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import type { Friend } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  open: boolean
  friends: readonly Friend[]
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), { saving: false })
const emit = defineEmits<{
  close: []
  create: [name: string, memberIds: number[]]
}>()
const name = shallowRef('')
const selectedIds = ref<number[]>([])

watch(() => props.open, (open) => {
  if (!open) {
    name.value = ''
    selectedIds.value = []
  }
})

function toggle(id: number): void {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter((value) => value !== id)
    : [...selectedIds.value, id]
}

function submit(): void {
  const clean = name.value.trim()
  if (clean.length < 2 || clean.length > 20) return
  emit('create', clean, selectedIds.value)
}
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="group-sheet" role="dialog" aria-modal="true" aria-labelledby="group-title">
      <button class="close-button" type="button" aria-label="关闭" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <h2 id="group-title">创建群聊</h2>

      <label class="group-name">
        <span>群聊名称</span>
        <input v-model="name" class="field" autofocus maxlength="20" placeholder="例如：产品讨论组" />
      </label>

      <div class="member-heading">
        <span class="member-label">邀请好友</span>
        <span class="member-count">已选 {{ selectedIds.length }} 人</span>
      </div>

      <div class="friend-list">
        <div
          v-for="friend in friends"
          :key="friend.friendId"
          class="friend-row"
          :class="{ 'friend-row--selected': selectedIds.includes(friend.friendId) }"
          role="checkbox"
          :aria-checked="selectedIds.includes(friend.friendId)"
          tabindex="0"
          @click="toggle(friend.friendId)"
          @keydown.enter.space.prevent="toggle(friend.friendId)"
        >
          <UserAvatar :name="friend.remark || friend.nickname" :avatar="friend.avatar" :size="38" />
          <span class="friend-name">{{ friend.remark || friend.nickname }}</span>
          <span class="checkbox">
            <UiIcon v-if="selectedIds.includes(friend.friendId)" name="check" :size="14" />
          </span>
        </div>
        <p v-if="friends.length === 0" class="empty-tip">先添加好友，再邀请他们进入群聊。</p>
      </div>

      <button
        class="primary-button create-button"
        type="button"
        :disabled="name.trim().length < 2 || saving"
        @click="submit"
      >
        {{ saving ? '正在创建…' : `创建群聊${selectedIds.length ? ` · ${selectedIds.length + 1} 人` : ''}` }}
      </button>
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
  backdrop-filter: blur(7px);
  -webkit-backdrop-filter: blur(7px);
}

.group-sheet {
  position: relative;
  display: flex;
  width: min(100%, 440px);
  max-height: calc(100dvh - 40px);
  padding: 28px 26px 22px;
  flex-direction: column;
  border-radius: 22px;
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow: hidden;
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
}
.close-button:hover { background: var(--button-hover); }
.close-button .ui-icon { width: 16px; }

.group-sheet h2 {
  margin: 0 0 4px;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.group-name {
  display: grid;
  margin-top: 18px;
  gap: 7px;
}
.group-name > span {
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 600;
}

.member-heading {
  display: flex;
  margin: 18px 0 8px;
  align-items: center;
  justify-content: space-between;
}
.member-label {
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 600;
}
.member-count {
  color: var(--blue);
  font-size: 11px;
  font-weight: 600;
}

.friend-list {
  min-height: 80px;
  max-height: 320px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--separator-strong) transparent;
}

.friend-row {
  display: flex;
  padding: 8px 10px;
  align-items: center;
  gap: 11px;
  border-radius: 12px;
  cursor: pointer;
  transition: background-color 150ms ease;
}
.friend-row:hover {
  background: var(--hover);
}
.friend-row--selected {
  background: var(--active);
}
.friend-row--selected:hover {
  background: color-mix(in srgb, var(--blue) 14%, transparent);
}

.friend-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkbox {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  place-items: center;
  border: 2px solid var(--separator-strong);
  border-radius: 7px;
  background: transparent;
  transition: background-color 150ms ease, border-color 150ms ease;
}
.checkbox .ui-icon { width: 14px; }
.friend-row--selected .checkbox {
  border-color: var(--blue);
  background: var(--blue);
  color: #fff;
}

.empty-tip {
  margin: 40px 0;
  color: var(--ink-soft);
  font-size: 13px;
  text-align: center;
}

.create-button {
  width: 100%;
  margin-top: 16px;
  flex-shrink: 0;
}
</style>
