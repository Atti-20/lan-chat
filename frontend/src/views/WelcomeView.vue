<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import UserAvatar from '../components/base/UserAvatar.vue'
import { ApiError } from '../services/api'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'

const auth = useAuth()
const toast = useToast()
const nickname = shallowRef('')
const selectedAvatar = shallowRef('emoji:🫧:#5AC8FA')
const saving = shallowRef(false)
const error = shallowRef('')
const avatars = ['🫧', '🐼', '🐰', '🦊', '🐧', '🦉', '🌊', '🌙']
const displayName = computed(() => nickname.value.trim() || auth.currentUser.value?.nickname || '新朋友')

onMounted(async () => {
  const user = auth.currentUser.value || await auth.hydrate()
  if (!user) {
    window.location.replace('/')
    return
  }
  nickname.value = user.nickname
  if (user.avatar) selectedAvatar.value = user.avatar
})

async function finish(): Promise<void> {
  const cleanName = nickname.value.trim()
  if (cleanName.length < 1 || cleanName.length > 16) {
    error.value = '昵称需为 1–16 个字符'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await auth.updateProfile({ nickname: cleanName, avatar: selectedAvatar.value })
    toast.push('资料已保存，欢迎来到 LanChat', 'success', 1400)
    window.location.assign(`${import.meta.env.BASE_URL.replace(/\/$/, '')}/chat`)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="welcome-page">
    <section class="welcome-sheet glass-surface">
      <header class="welcome-header">
        <div class="step-pill"><span /> 只差一步</div>
        <h1>让朋友一眼认出你。</h1>
        <p>选择一个轻盈的头像和称呼。它们会出现在私聊、群组与在线列表中。</p>
      </header>

      <div class="profile-stage">
        <UserAvatar :name="displayName" :avatar="selectedAvatar" :size="112" online />
        <div class="profile-caption">
          <strong>{{ displayName }}</strong>
          <span>已连接到 LanChat</span>
        </div>
      </div>

      <form class="welcome-form" @submit.prevent="finish">
        <fieldset>
          <legend>选择头像</legend>
          <div class="avatar-grid">
            <button
              v-for="(emoji, index) in avatars"
              :key="emoji"
              type="button"
              class="avatar-choice"
              :class="{ 'avatar-choice--selected': selectedAvatar.includes(emoji) }"
              :aria-label="`选择头像 ${emoji}`"
              :aria-pressed="selectedAvatar.includes(emoji)"
              @click="selectedAvatar = `emoji:${emoji}:#${index % 2 ? '7667F5' : '5AC8FA'}`"
            >
              {{ emoji }}
            </button>
          </div>
        </fieldset>

        <label class="name-field">
          <span>你的称呼</span>
          <input v-model="nickname" class="field" maxlength="16" autocomplete="nickname" />
        </label>

        <p v-if="error" class="welcome-error" role="alert">{{ error }}</p>
        <button class="primary-button finish-button" type="submit" :disabled="saving">
          {{ saving ? '正在保存…' : '进入聊天' }}
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M4 10h12m-5-5 5 5-5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.welcome-page {
  display: grid;
  min-height: 100dvh;
  padding: 40px 20px;
  place-items: center;
}
.welcome-sheet {
  display: grid;
  width: min(100%, 880px);
  padding: clamp(28px, 6vw, 62px);
  grid-template-columns: 0.9fr 1.1fr;
  gap: clamp(38px, 7vw, 80px);
  border-radius: 42px 42px 42px 20px;
}
.welcome-header { grid-column: 1 / -1; max-width: 660px; }
.step-pill {
  display: inline-flex;
  padding: 7px 12px;
  align-items: center;
  gap: 8px;
  border: 1px solid rgba(255,255,255,.8);
  border-radius: 999px;
  color: #2672bd;
  font-size: 12px;
  font-weight: 700;
  background: rgba(255,255,255,.46);
}
.step-pill span { width: 8px; height: 8px; border-radius: 50%; background: var(--blue); box-shadow: 0 0 0 5px rgba(10,132,255,.1); }
.welcome-header h1 { margin: 18px 0 12px; font-size: clamp(36px, 6vw, 58px); letter-spacing: -.055em; line-height: 1.04; }
.welcome-header p { max-width: 610px; margin: 0; color: var(--ink-soft); font-size: 16px; line-height: 1.7; }
.profile-stage { position: relative; display: grid; min-height: 310px; place-items: center; align-content: center; gap: 20px; }
.preview-ring { position: absolute; inset: 18px; border: 1px solid rgba(10,132,255,.14); border-radius: 46% 54% 52% 48%; transform: rotate(-8deg); }
.preview-ring::before { position: absolute; inset: 28px; border: 1px solid rgba(118,103,245,.12); border-radius: 54% 46% 48% 52%; content: ""; transform: rotate(16deg); }
.preview-ring i { position: absolute; width: 10px; height: 10px; border: 3px solid white; border-radius: 50%; background: var(--cyan); box-shadow: 0 4px 10px rgba(10,132,255,.25); }
.preview-ring i:nth-child(1) { top: 16%; right: 14%; }
.preview-ring i:nth-child(2) { bottom: 19%; left: 9%; background: var(--green); }
.preview-ring i:nth-child(3) { right: 5%; bottom: 29%; background: var(--violet); }
.profile-caption { z-index: 1; display: grid; gap: 3px; text-align: center; }
.profile-caption strong { font-size: 20px; }
.profile-caption span { color: var(--ink-soft); font-size: 12px; }
.welcome-form { display: grid; align-content: center; gap: 22px; }
.welcome-form fieldset { padding: 0; border: 0; }
.welcome-form legend,
.name-field > span { margin-bottom: 11px; color: #35506e; font-size: 13px; font-weight: 700; }
.avatar-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.avatar-choice {
  aspect-ratio: 1;
  border: 1px solid rgba(255,255,255,.74);
  border-radius: 19px;
  font-size: 27px;
  background: rgba(255,255,255,.42);
  box-shadow: inset 0 1px 0 #fff;
  cursor: pointer;
  transition: 200ms var(--ease-liquid);
}
.avatar-choice:hover { transform: translateY(-3px); background: rgba(255,255,255,.68); }
.avatar-choice--selected { border-color: rgba(10,132,255,.48); background: rgba(217,238,255,.8); box-shadow: 0 0 0 4px rgba(10,132,255,.09), inset 0 1px 0 #fff; transform: scale(1.04); }
.name-field { display: grid; }
.welcome-error { margin: -8px 0 0; color: var(--coral); font-size: 13px; }
.finish-button { display: flex; width: 100%; align-items: center; justify-content: center; gap: 8px; }
.finish-button svg { width: 18px; }

@media (max-width: 720px) {
  .welcome-page { padding: 18px 12px; }
  .welcome-sheet { grid-template-columns: 1fr; gap: 28px; border-radius: 30px 30px 30px 16px; }
  .profile-stage { min-height: 230px; }
  .welcome-header h1 { font-size: 40px; }
}

.welcome-page { padding: 30px 20px; }
.welcome-sheet {
  width: min(100%, 760px);
  padding: clamp(28px, 5vw, 48px);
  grid-template-columns: 0.85fr 1.15fr;
  gap: 44px;
  border-radius: 28px;
  background: var(--surface-raise);
  box-shadow: 0 18px 50px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}
.step-pill {
  padding: 6px 10px;
  border: 0;
  color: var(--blue);
  background: var(--active);
}
.step-pill span { width: 6px; height: 6px; box-shadow: none; }
.welcome-header h1 { margin: 16px 0 10px; font-size: clamp(34px, 5vw, 48px); }
.welcome-header p { color: var(--ink-soft); font-size: 15px; }
.profile-stage {
  min-height: 250px;
  border-radius: 22px;
  background: var(--hover);
}
.avatar-choice {
  border: 0;
  border-radius: 50%;
  background: var(--fill);
  box-shadow: none;
}
.avatar-choice:hover { transform: none; background: var(--button-hover); }
.avatar-choice--selected {
  border: 2px solid rgba(0, 122, 255, 0.46);
  background: var(--active);
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.07);
  transform: none;
}
.welcome-form legend,
.name-field > span { color: var(--ink-soft); font-weight: 600; }

@media (max-width: 720px) {
  .welcome-page { padding: 14px; }
  .welcome-sheet { grid-template-columns: 1fr; gap: 24px; border-radius: 24px; }
  .profile-stage { min-height: 200px; }
  .welcome-header h1 { font-size: 36px; }
}
</style>
