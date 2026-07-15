<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import type { AdminUser } from '../../types'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  user: AdminUser | null
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), { saving: false })
const emit = defineEmits<{
  close: []
  reset: [newPassword: string]
}>()

const newPassword = shallowRef('')
const confirmPassword = shallowRef('')
const error = shallowRef('')
const displayName = computed(() => props.user?.nickname || props.user?.username || '')

watch(() => props.user?.id, () => {
  newPassword.value = ''
  confirmPassword.value = ''
  error.value = ''
})

function submit(): void {
  error.value = ''
  if (newPassword.value.length < 8 || newPassword.value.length > 20
    || !/[A-Za-z]/.test(newPassword.value) || !/\d/.test(newPassword.value)) {
    error.value = '新密码需为 8–20 位，并同时包含字母和数字'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  emit('reset', newPassword.value)
}
</script>

<template>
  <div v-if="user" class="reset-backdrop" role="presentation" @click.self="emit('close')">
    <section class="reset-sheet" role="dialog" aria-modal="true" aria-labelledby="reset-password-title">
      <button class="close-button" type="button" aria-label="关闭" :disabled="saving" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <span class="reset-icon" aria-hidden="true"><UiIcon name="lock" :size="22" /></span>
      <p class="reset-kicker">账号安全</p>
      <h2 id="reset-password-title">为 {{ displayName }} 重置密码</h2>
      <p class="reset-desc">
        @{{ user.username }} 保存新密码后，该账号在所有设备上的登录会立即失效。
      </p>

      <form class="reset-form" @submit.prevent="submit">
        <label>
          <span>新密码</span>
          <input
            v-model="newPassword"
            class="field"
            type="password"
            autocomplete="new-password"
            maxlength="20"
            placeholder="8–20 位字母与数字"
          />
        </label>
        <label>
          <span>确认新密码</span>
          <input
            v-model="confirmPassword"
            class="field"
            type="password"
            autocomplete="new-password"
            maxlength="20"
            placeholder="再次输入新密码"
          />
        </label>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>

        <button class="primary-button" type="submit" :disabled="saving">
          {{ saving ? '正在重置…' : '确认重置密码' }}
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.reset-backdrop {
  position: fixed;
  z-index: 112;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}
.reset-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  padding: 26px;
  border: 1px solid var(--glass-border);
  border-radius: 22px;
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
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
.close-button:disabled { opacity: .45; cursor: default; }
.reset-icon { display: grid; width: 46px; height: 46px; margin-bottom: 14px; place-items: center; border-radius: 14px; color: var(--blue); background: var(--active); }
.reset-kicker { margin: 0 0 5px; color: var(--blue); font-size: 9px; font-weight: 800; letter-spacing: .14em; }
.reset-sheet h2 { max-width: calc(100% - 36px); margin: 0; font-size: 20px; letter-spacing: -.025em; }
.reset-desc { margin: 7px 0 18px; color: var(--ink-soft); font-size: 12px; line-height: 1.55; }
.reset-form { display: grid; gap: 14px; }
.reset-form label { display: grid; gap: 6px; }
.reset-form label span { color: var(--ink-soft); font-size: 12px; font-weight: 600; }
.form-error { margin: -2px 0 0; color: var(--coral); font-size: 12px; line-height: 1.5; }
.reset-form .primary-button { width: 100%; margin-top: 5px; }

@media (max-width: 520px) {
  .reset-backdrop { padding: 14px; }
  .reset-sheet { padding: 24px 20px 20px; border-radius: 20px; }
}
</style>
