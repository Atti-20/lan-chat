<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import type { User } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  open: boolean
  user: User
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), { saving: false })
const emit = defineEmits<{
  close: []
  save: [payload: { nickname: string; avatar: string }]
  logout: []
}>()
const nickname = shallowRef('')
const avatar = shallowRef('')
const emojis = ['🫧', '🐼', '🐰', '🦊', '🐧', '🦉', '🌊', '🌙']

watch(() => [props.open, props.user.nickname, props.user.avatar] as const, ([open]) => {
  if (open) {
    nickname.value = props.user.nickname
    avatar.value = props.user.avatar || 'emoji:🫧:#5AC8FA'
  }
}, { immediate: true })
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="profile-sheet glass-surface" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <button class="close-button" type="button" aria-label="关闭" @click="emit('close')">×</button>
      <UserAvatar :name="nickname || user.nickname" :avatar="avatar" :size="88" online />
      <h2 id="profile-title">个人资料</h2>
      <p>@{{ user.username }}</p>
      <div class="emoji-row">
        <button v-for="(emoji, index) in emojis" :key="emoji" type="button" :class="{ selected: avatar.includes(emoji) }" @click="avatar = `emoji:${emoji}:#${index % 2 ? '7667F5' : '5AC8FA'}`">{{ emoji }}</button>
      </div>
      <label><span>昵称</span><input v-model="nickname" class="field" maxlength="16" /></label>
      <button class="primary-button" type="button" :disabled="saving || !nickname.trim()" @click="emit('save', { nickname: nickname.trim(), avatar })">{{ saving ? '正在保存…' : '保存资料' }}</button>
      <button class="logout-button" type="button" @click="emit('logout')">退出登录</button>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; padding: 20px; place-items: center; background: rgba(39,64,92,.18); backdrop-filter: blur(10px); }
.profile-sheet { position: relative; display: grid; width: min(100%, 400px); padding: 34px 30px 26px; justify-items: center; border-radius: 32px 32px 32px 16px; }
.close-button { position: absolute; top: 17px; right: 17px; width: 36px; height: 36px; border: 1px solid rgba(255,255,255,.72); border-radius: 13px; color: #60748a; font-size: 22px; background: rgba(255,255,255,.45); cursor: pointer; }
.profile-sheet h2 { margin: 15px 0 2px; font-size: 24px; letter-spacing: -.04em; }.profile-sheet > p { margin: 0; color: var(--ink-soft); font-size: 11px; }
.emoji-row { display: grid; width: 100%; margin: 24px 0 18px; grid-template-columns: repeat(8, 1fr); gap: 5px; }.emoji-row button { aspect-ratio: 1; padding: 0; border: 1px solid transparent; border-radius: 10px; font-size: 19px; background: rgba(255,255,255,.34); cursor: pointer; }.emoji-row button.selected { border-color: rgba(10,132,255,.35); background: rgba(210,235,255,.68); transform: scale(1.08); }
.profile-sheet label { display: grid; width: 100%; gap: 8px; }.profile-sheet label span { color: #526c85; font-size: 11px; font-weight: 700; }
.profile-sheet .primary-button { width: 100%; margin-top: 17px; }.logout-button { margin-top: 16px; border: 0; color: #ce4752; font-size: 11px; font-weight: 700; background: transparent; cursor: pointer; }
@media (max-width: 430px) { .emoji-row { grid-template-columns: repeat(4, 1fr); } }

.modal-backdrop { background: rgba(28, 28, 30, 0.18); backdrop-filter: blur(7px); }
.profile-sheet {
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 20px 60px rgba(29, 29, 31, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.96);
}
.close-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
}
.emoji-row button { border-radius: 50%; background: var(--fill); }
.emoji-row button.selected { border-color: rgba(0, 122, 255, 0.38); background: rgba(0, 122, 255, 0.09); transform: none; }
.profile-sheet label span { color: var(--ink-soft); font-weight: 600; }
</style>
