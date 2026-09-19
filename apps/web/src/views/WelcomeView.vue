<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import UserAvatar from '../components/base/UserAvatar.vue'
import UiIcon from '../components/base/UiIcon.vue'
import { api, ApiError } from '../services/api'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { navigateToApp } from '../platform/appNavigation'
import {
  createTextAvatar,
  isTextAvatar,
  textAvatarInitial,
} from '../services/textAvatar'

const auth = useAuth()
const toast = useToast()
const nickname = shallowRef('')
const selectedAvatar = shallowRef('text')
const saving = shallowRef(false)
const uploadingAvatar = shallowRef(false)
const error = shallowRef('')
const avatarInput = ref<HTMLInputElement | null>(null)
const displayName = computed(() => nickname.value.trim() || auth.currentUser.value?.nickname || '新朋友')
const isCustomAvatar = computed(() => Boolean(selectedAvatar.value)
  && !isTextAvatar(selectedAvatar.value)
  && !selectedAvatar.value.startsWith('emoji:')
  && !selectedAvatar.value.startsWith('svg:'))
const isTextChoice = computed(() => isTextAvatar(selectedAvatar.value))
const previewAvatar = computed(() => isTextChoice.value
  ? createTextAvatar(displayName.value)
  : selectedAvatar.value)

function usesTextAvatar(avatar: string | null | undefined): boolean {
  return isTextAvatar(avatar)
    || avatar?.startsWith('emoji:') === true
    || avatar?.startsWith('svg:') === true
}

onMounted(async () => {
  const user = auth.currentUser.value || await auth.hydrate()
  if (!user) {
    navigateToApp('/', true)
    return
  }
  nickname.value = user.nickname
  selectedAvatar.value = usesTextAvatar(user.avatar)
    ? createTextAvatar(user.nickname)
    : (user.avatar || createTextAvatar(user.nickname))
})

function chooseTextAvatar(): void {
  selectedAvatar.value = createTextAvatar(displayName.value)
}

function chooseAvatarFile(): void {
  avatarInput.value?.click()
}

async function onAvatarFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '请选择图片文件'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    error.value = '头像图片不能超过 5MB'
    return
  }

  uploadingAvatar.value = true
  error.value = ''
  try {
    const result = await api.files.uploadAvatar(file)
    selectedAvatar.value = result.thumbnailUrl || result.url
    toast.push('头像已上传', 'success', 1200)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '头像上传失败，请稍后重试'
  } finally {
    uploadingAvatar.value = false
  }
}

async function finish(): Promise<void> {
  const cleanName = nickname.value.trim()
  if (cleanName.length < 1 || cleanName.length > 16) {
    error.value = '昵称需为 1–16 个字符'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const avatar = isTextAvatar(selectedAvatar.value)
      ? createTextAvatar(cleanName)
      : selectedAvatar.value
    await auth.updateProfile({ nickname: cleanName, avatar })
    toast.push('资料已保存，欢迎来到 MeshX', 'success', 1400)
    navigateToApp('/chat')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="welcome-page">
    <section class="welcome-sheet glass-surface apple-float-surface">
      <header class="welcome-header">
        <div class="step-pill"><span /> 只差一步</div>
        <h1>让朋友一眼认出你。</h1>
        <p>使用昵称首字母，或上传自己的图片。它们会出现在私聊、群组与在线列表中。</p>
      </header>

      <div class="profile-stage">
        <UserAvatar :name="displayName" :avatar="previewAvatar" :size="112" online />
        <div class="profile-caption">
          <strong>{{ displayName }}</strong>
          <span>已连接到 MeshX</span>
        </div>
      </div>

      <form class="welcome-form" @submit.prevent="finish">
        <fieldset>
          <legend>选择头像</legend>
          <div class="avatar-grid">
            <button
              type="button"
              class="avatar-choice avatar-choice--text"
              :class="{ 'avatar-choice--selected': isTextChoice }"
              aria-label="使用昵称首字符作为头像"
              :aria-pressed="isTextChoice"
              @click="chooseTextAvatar"
            >{{ textAvatarInitial(displayName) }}</button>
            <button
              type="button"
              class="avatar-choice avatar-choice--upload"
              :class="{ 'avatar-choice--selected': isCustomAvatar }"
              :disabled="uploadingAvatar"
              aria-label="上传自定义头像"
              :aria-pressed="isCustomAvatar"
              @click="chooseAvatarFile"
            >
              <UiIcon name="edit" :size="22" />
              <small>{{ uploadingAvatar ? '上传中' : '上传图片' }}</small>
            </button>
          </div>
          <input ref="avatarInput" class="sr-only" type="file" accept="image/*" @change="onAvatarFileChange" />
        </fieldset>

        <label class="name-field">
          <span>你的称呼</span>
          <input v-model="nickname" class="field" maxlength="16" autocomplete="nickname" />
        </label>

        <p v-if="error" class="welcome-error" role="alert">{{ error }}</p>
        <button class="primary-button finish-button" type="submit" :disabled="saving || uploadingAvatar">
          {{ uploadingAvatar ? '正在上传头像…' : saving ? '正在保存…' : '进入聊天' }}
          <UiIcon name="arrow-right" :size="18" />
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.welcome-page { display: grid; min-height: 100dvh; padding: max(30px, env(safe-area-inset-top)) max(20px, env(safe-area-inset-right)) max(30px, env(safe-area-inset-bottom)) max(20px, env(safe-area-inset-left)); place-items: center; }
.welcome-sheet { display: grid; min-width: 0; width: min(100%, 760px); padding: clamp(28px, 5vw, 48px); grid-template-columns: minmax(0, .85fr) minmax(0, 1.15fr); gap: 44px; border-radius: 28px; }
.welcome-header { grid-column: 1 / -1; max-width: 660px; }
.step-pill { display: inline-flex; padding: 6px 10px; align-items: center; gap: var(--space-2); border-radius: var(--radius-pill); color: var(--accent-text); font-size: var(--font-caption); font-weight: 700; background: var(--active); }
.step-pill span { width: 6px; height: 6px; border-radius: 50%; background: var(--blue); }
.welcome-header h1 { margin: 16px 0 10px; font-size: clamp(34px, 5vw, 48px); letter-spacing: -.055em; line-height: 1.1; text-wrap: balance; }
.welcome-header p { max-width: 610px; margin: 0; color: var(--ink-soft); font-size: var(--font-body-lg); line-height: 1.7; }
.profile-stage { position: relative; display: grid; min-width: 0; min-height: 250px; padding: var(--space-4); place-items: center; align-content: center; gap: var(--space-5); border-radius: var(--radius-sheet); background: var(--hover); }
.profile-caption { display: grid; min-width: 0; gap: var(--space-1); text-align: center; overflow-wrap: anywhere; }
.profile-caption strong { font-size: var(--font-title); }
.profile-caption span { color: var(--ink-soft); font-size: var(--font-caption); }
.welcome-form { display: grid; min-width: 0; align-content: center; gap: var(--space-6); }
.welcome-form fieldset { min-width: 0; padding: 0; margin: 0; border: 0; }
.welcome-form legend, .name-field > span { margin-bottom: var(--space-3); color: var(--ink-soft); font-size: var(--font-body-sm); font-weight: 600; }
.avatar-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 132px)); gap: 12px; }
.avatar-choice { aspect-ratio: 1; min-width: 0; padding: 0; border: 2px solid transparent; border-radius: 50%; font-size: 27px; background: var(--fill); cursor: pointer; transition: background-color var(--duration-fast) ease, border-color var(--duration-fast) ease; }
.avatar-choice:hover { background: var(--button-hover); }
.avatar-choice--selected { border-color: var(--accent-text); background: var(--active); box-shadow: 0 0 0 3px var(--active); }
.avatar-choice--text { color: var(--on-accent); font-weight: 750; background: linear-gradient(145deg, #5856D6, #4644C4); }
.avatar-choice--text:hover { background: var(--action-hover); }
.avatar-choice--upload { display: grid; place-items: center; align-content: center; gap: var(--space-1); color: var(--accent-text); }
.avatar-choice--upload small { font-size: var(--font-micro); font-weight: 650; }
.avatar-choice--upload:disabled { cursor: wait; opacity: .65; }
.name-field { display: grid; min-width: 0; }
.welcome-error { margin: -8px 0 0; color: var(--danger); font-size: var(--font-body-sm); }
.finish-button { display: flex; width: 100%; align-items: center; justify-content: center; gap: var(--space-2); }
@media (max-width: 720px) {
  .welcome-page { padding: max(14px, env(safe-area-inset-top)) max(14px, env(safe-area-inset-right)) max(14px, env(safe-area-inset-bottom)) max(14px, env(safe-area-inset-left)); }
  .welcome-sheet { grid-template-columns: minmax(0, 1fr); gap: var(--space-6); padding: var(--space-6); border-radius: var(--radius-sheet); }
  .profile-stage { min-height: 200px; }
  .welcome-header h1 { font-size: clamp(28px, 8vw, 36px); }
}
</style>
