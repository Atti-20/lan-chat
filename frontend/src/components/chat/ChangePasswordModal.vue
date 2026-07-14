<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { api, ApiError } from '../../services/api'
import { useToast } from '../../composables/useToast'

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  passwordChanged: []
}>()
const toast = useToast()
const oldPassword = shallowRef('')
const newPassword = shallowRef('')
const confirmPassword = shallowRef('')
const error = shallowRef('')
const saving = shallowRef(false)

watch(() => props.open, (open) => {
  if (open) {
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    error.value = ''
  }
})

async function submit(): Promise<void> {
  error.value = ''
  if (!oldPassword.value) {
    error.value = '请输入当前密码'
    return
  }
  if (newPassword.value.length < 8 || !/[A-Za-z]/.test(newPassword.value) || !/\d/.test(newPassword.value)) {
    error.value = '新密码至少 8 位，并同时包含字母和数字'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  if (newPassword.value === oldPassword.value) {
    error.value = '新密码不能与旧密码相同'
    return
  }

  saving.value = true
  try {
    await api.user.changePassword(oldPassword.value, newPassword.value)
    toast.push('密码已修改，请重新登录', 'success', 2000)
    emit('passwordChanged')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '修改密码失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="password-sheet" role="dialog" aria-modal="true" aria-labelledby="password-title">
      <button class="close-button" type="button" aria-label="关闭" @click="emit('close')">
        <svg viewBox="0 0 24 24" fill="none"><path d="M18 6 6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
      </button>

      <h2 id="password-title">修改密码</h2>
      <p class="password-desc">修改后所有设备将被登出，需要重新登录</p>

      <form class="password-form" @submit.prevent="submit">
        <label>
          <span>当前密码</span>
          <input v-model="oldPassword" class="field" type="password" autocomplete="current-password" placeholder="输入当前密码" />
        </label>
        <label>
          <span>新密码</span>
          <input v-model="newPassword" class="field" type="password" autocomplete="new-password" maxlength="20" placeholder="8–20 位字母与数字" />
        </label>
        <label>
          <span>确认新密码</span>
          <input v-model="confirmPassword" class="field" type="password" autocomplete="new-password" maxlength="20" placeholder="再次输入新密码" />
        </label>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>

        <button class="primary-button" type="submit" :disabled="saving">
          {{ saving ? '正在修改…' : '确认修改' }}
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 110;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(7px);
  -webkit-backdrop-filter: blur(7px);
}

.password-sheet {
  position: relative;
  display: grid;
  width: min(100%, 400px);
  padding: 28px 26px 24px;
  gap: 2px;
  border-radius: 22px;
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
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
.close-button svg { width: 16px; }

.password-sheet h2 { margin: 0; font-size: 20px; letter-spacing: -0.02em; }
.password-desc { margin: 4px 0 16px; color: var(--ink-soft); font-size: 12px; line-height: 1.5; }

.password-form { display: grid; gap: 14px; }
.password-form label { display: grid; gap: 6px; }
.password-form label span { color: var(--ink-soft); font-size: 12px; font-weight: 600; }

.form-error { margin: -2px 0 0; color: var(--coral); font-size: 12px; line-height: 1.5; }

.password-form .primary-button { width: 100%; margin-top: 6px; }
</style>
