<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import type { Friend } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'

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
    <section class="modal-sheet glass-surface" role="dialog" aria-modal="true" aria-labelledby="group-title">
      <header>
        <div><h2 id="group-title">创建群聊</h2></div>
        <button type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>
      <label class="group-name">
        <span>群聊名称</span>
        <input v-model="name" class="field" autofocus maxlength="20" placeholder="例如：产品讨论组" />
      </label>
      <div class="member-heading"><strong>邀请好友</strong><span>已选 {{ selectedIds.length }} 人</span></div>
      <div class="friend-grid">
        <button
          v-for="friend in friends"
          :key="friend.friendId"
          type="button"
          :class="{ selected: selectedIds.includes(friend.friendId) }"
          :aria-pressed="selectedIds.includes(friend.friendId)"
          @click="toggle(friend.friendId)"
        >
          <UserAvatar :name="friend.remark || friend.nickname" :avatar="friend.avatar" :size="42" />
          <span>{{ friend.remark || friend.nickname }}</span>
          <i>✓</i>
        </button>
        <p v-if="friends.length === 0">先添加好友，再邀请他们进入群聊。</p>
      </div>
      <button class="primary-button create-button" type="button" :disabled="name.trim().length < 2 || saving" @click="submit">
        {{ saving ? '正在创建…' : `创建群聊${selectedIds.length ? ` · ${selectedIds.length + 1} 人` : ''}` }}
      </button>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; padding: 20px; place-items: center; background: rgba(39,64,92,.18); backdrop-filter: blur(10px); }
.modal-sheet { display: flex; width: min(100%, 520px); max-height: min(760px, 90dvh); padding: 24px; flex-direction: column; border-radius: 30px 30px 30px 15px; }
.modal-sheet header { display: flex; align-items: start; justify-content: space-between; }.modal-sheet header p { margin: 0 0 4px; color: #4f80ad; font-family: "SF Mono", monospace; font-size: 9px; font-weight: 700; letter-spacing: .14em; }.modal-sheet h2 { margin: 0; font-size: 25px; letter-spacing: -.04em; }.modal-sheet header button { width: 36px; height: 36px; border: 1px solid rgba(255,255,255,.72); border-radius: 13px; color: #60748a; font-size: 22px; background: rgba(255,255,255,.45); cursor: pointer; }
.group-name { display: grid; margin-top: 22px; gap: 8px; }.group-name > span,.member-heading strong { color: #526c85; font-size: 11px; font-weight: 700; }
.member-heading { display: flex; margin: 20px 0 9px; align-items: center; justify-content: space-between; }.member-heading span { color: var(--blue); font-size: 10px; font-weight: 700; }
.friend-grid { display: grid; min-height: 140px; max-height: 310px; padding-right: 3px; overflow-y: auto; grid-template-columns: 1fr 1fr; gap: 7px; }
.friend-grid button { position: relative; display: flex; min-width: 0; padding: 9px; align-items: center; gap: 9px; border: 1px solid rgba(255,255,255,.54); border-radius: 16px; text-align: left; background: rgba(255,255,255,.3); cursor: pointer; }
.friend-grid button > span { overflow: hidden; flex: 1; font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.friend-grid button i { display: grid; width: 19px; height: 19px; place-items: center; border: 1px solid rgba(112,139,166,.24); border-radius: 7px; color: transparent; font-size: 10px; font-style: normal; }
.friend-grid button.selected { border-color: rgba(10,132,255,.3); background: rgba(215,237,255,.62); }.friend-grid button.selected i { border-color: transparent; color: white; background: var(--blue); }
.friend-grid > p { grid-column: 1 / -1; margin: 40px 0; color: var(--ink-soft); font-size: 12px; text-align: center; }
.create-button { width: 100%; margin-top: 20px; }
@media (max-width: 480px) { .friend-grid { grid-template-columns: 1fr; } }

.modal-backdrop { background: rgba(28, 28, 30, 0.18); backdrop-filter: blur(7px); }
.modal-sheet {
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
.group-name > span,
.member-heading strong { color: var(--ink-soft); font-weight: 600; }
.friend-grid button {
  border: 0;
  border-radius: 12px;
  background: var(--fill);
}
.friend-grid button.selected { background: rgba(0, 122, 255, 0.09); }
.friend-grid button.selected i { background: var(--blue); }
</style>
