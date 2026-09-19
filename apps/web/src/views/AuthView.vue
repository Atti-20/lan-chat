<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, shallowRef, useTemplateRef } from 'vue'
import BrandLogo from '../components/base/BrandLogo.vue'
import UiIcon from '../components/base/UiIcon.vue'
import NodeDiscoveryPanel from '../components/nodes/NodeDiscoveryPanel.vue'
import { ApiError, api } from '../services/api'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { useTheme } from '../composables/useTheme'
import { navigateToApp } from '../platform/appNavigation'
import { readLastUsername } from '../utils/storage'

const auth = useAuth()
const toast = useToast()
const { mode: themeMode, toggle: toggleTheme } = useTheme()
const mode = shallowRef<'login' | 'register'>('login')
const username = shallowRef('')
const password = shallowRef('')
const nickname = shallowRef('')
const error = shallowRef('')
const autoLogging = shallowRef(false)
const registrationEnabled = shallowRef(true)
const passwordInput = useTemplateRef<HTMLInputElement>('passwordInput')
// Set this during setup, not only in onMounted. Otherwise `<details>` first
// renders open and its queued native toggle event can reopen node discovery
// after the compact media query has already matched.
const compactViewport = shallowRef(typeof window !== 'undefined'
  && window.matchMedia('(max-width: 860px)').matches)
const nodeDiscoveryOpen = shallowRef(false)
let compactMedia: MediaQueryList | null = null

const heading = computed(() => mode.value === 'login' ? '回到对话' : '创建你的空间')
const submitLabel = computed(() => {
  if (auth.loading.value) return mode.value === 'login' ? '正在连接…' : '正在创建…'
  return mode.value === 'login' ? '登录 MeshX' : '创建并进入'
})

onMounted(async () => {
  // Native Android/iOS WebViews can expose a portrait viewport around 800 CSS
  // pixels wide on high-density devices. Keep the compact login treatment in
  // that range too; otherwise the discovery panel consumes the form's height.
  compactMedia = window.matchMedia('(max-width: 860px)')
  compactViewport.value = compactMedia.matches
  const handleCompactChange = (event: MediaQueryListEvent) => {
    compactViewport.value = event.matches
    if (!event.matches) nodeDiscoveryOpen.value = true
  }
  compactMedia.addEventListener('change', handleCompactChange)
  removeCompactListener = () => compactMedia?.removeEventListener('change', handleCompactChange)
  clearPasswordInput()
  // Access Token 只保存在当前标签页；刷新时尝试通过 HttpOnly Cookie 恢复。
  autoLogging.value = true
  try {
    const user = await auth.hydrate()
    if (user) {
      navigateToApp('/chat', true)
      return
    }
  } catch {
    // 没有有效设备会话时继续显示登录页。
  } finally {
    autoLogging.value = false
  }
  await nextTick()
  clearPasswordInput()
  try {
    const node = await api.node.info()
    registrationEnabled.value = node.selfRegistrationEnabled
    if (!registrationEnabled.value) mode.value = 'login'
  } catch {
    // 登录仍可继续；服务端会对注册策略做最终校验。
  }
  // 回填上次登录的用户名
  const lastUsername = readLastUsername()
  if (lastUsername) {
    username.value = lastUsername
  }
  await nextTick()
  clearPasswordInput()
})

let removeCompactListener: (() => void) | null = null

onBeforeUnmount(() => {
  removeCompactListener?.()
  removeCompactListener = null
})

function clearPasswordInput(): void {
  password.value = ''
  if (passwordInput.value) passwordInput.value.value = ''
}

function switchMode(next: 'login' | 'register'): void {
  mode.value = next
  error.value = ''
}

function handleNodeDiscoveryToggle(event: Event): void {
  nodeDiscoveryOpen.value = (event.currentTarget as HTMLDetailsElement).open
}

async function submit(): Promise<void> {
  error.value = ''
  const cleanUsername = username.value.trim()
  const cleanNickname = nickname.value.trim()
  if (!cleanUsername || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  if (mode.value === 'register') {
    if (cleanNickname.length < 2 || cleanNickname.length > 16) {
      error.value = '昵称需为 2–16 个字符'
      return
    }
    if (password.value.length < 8 || !/[A-Za-z]/.test(password.value) || !/\d/.test(password.value)) {
      error.value = '密码至少 8 位，并同时包含字母和数字'
      return
    }
  }

  try {
    if (mode.value === 'login') {
      await auth.login(cleanUsername, password.value)
      toast.push('已安全登录', 'success', 1200)
      navigateToApp('/chat', true)
    } else {
      await auth.register(cleanUsername, password.value, cleanNickname)
      toast.push('账号已创建', 'success', 1200)
      navigateToApp('/welcome')
    }
  } catch (cause) {
    const message = cause instanceof ApiError || cause instanceof Error
      ? cause.message
      : '操作失败，请稍后重试'
    error.value = message
    // 如果错误包含"其他设备"或"已登录"相关信息，显示更友好的提示
    if (message.includes('已登录') || message.includes('设备') || message.includes('踢')) {
      toast.push('该账号已在其他设备登录', 'warning', 3000)
    }
  }
}
</script>

<template>
  <main v-if="autoLogging" class="auth-page auth-page--auto">
    <div class="auto-login-state">
      <span class="auto-spinner" />
      <strong>正在自动登录…</strong>
    </div>
  </main>
  <main v-else class="auth-page">
    <button
      class="appearance-toggle"
      type="button"
      :aria-label="themeMode === 'dark' ? '切换为浅色模式' : '切换为深色模式'"
      :title="themeMode === 'dark' ? '切换为浅色模式' : '切换为深色模式'"
      @click="toggleTheme"
    >
      <UiIcon :name="themeMode === 'dark' ? 'sun' : 'moon'" :size="20" />
    </button>
    <section class="auth-story" aria-label="MeshX 简介">
      <div class="brand-mark" aria-hidden="true">
        <BrandLogo decorative />
      </div>

      <div class="story-copy">
        <p class="eyebrow">PRIVATE / LOCAL / CONNECTED</p>
        <h1>MeshX，让连接更自然。</h1>
        <p class="story-lead">消息、文件、群组，打开即用。</p>
      </div>

      <details
        class="node-discovery-disclosure"
        :open="!compactViewport || nodeDiscoveryOpen"
        @toggle="handleNodeDiscoveryToggle"
      >
        <summary>选择局域网节点 <UiIcon name="arrow-right" :size="16" /></summary>
        <NodeDiscoveryPanel />
      </details>

    </section>

    <section class="auth-card glass-surface apple-float-surface">
      <div class="auth-card-top">
        <p class="auth-kicker">MeshX</p>
        <h2>{{ heading }}</h2>
        <p>{{ mode === 'login' ? '输入账号以继续。' : '创建账号，即刻开聊。' }}</p>
      </div>

      <div v-if="registrationEnabled" class="mode-switch" role="tablist" aria-label="登录方式">
        <span class="mode-lens" :class="{ 'mode-lens--right': mode === 'register' }" />
        <button type="button" role="tab" :aria-selected="mode === 'login'" @click="switchMode('login')">登录</button>
        <button type="button" role="tab" :aria-selected="mode === 'register'" @click="switchMode('register')">注册</button>
      </div>
      <p v-else class="registration-policy">此私有节点由管理员创建账号。</p>

      <form class="auth-form" @submit.prevent="submit">
        <label v-if="mode === 'register'" class="field-group">
          <span>昵称</span>
          <input v-model="nickname" class="field" autocomplete="nickname" maxlength="16" placeholder="朋友会看到的名字" />
        </label>
        <label class="field-group">
          <span>用户名</span>
          <input v-model="username" class="field" autocomplete="username" maxlength="50" placeholder="例如 atti_20" />
        </label>
        <label class="field-group">
          <span>密码</span>
          <input
            ref="passwordInput"
            v-model="password"
            class="field"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :maxlength="mode === 'login' ? 72 : 20"
            :placeholder="mode === 'login' ? '输入账号密码' : '8–20 位字母与数字'"
          />
        </label>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="primary-button submit-button" type="submit" :disabled="auth.loading.value">
          {{ submitLabel }}
          <UiIcon name="arrow-right" :size="18" />
        </button>
      </form>

      <p class="privacy-note">令牌仅存于本地，退出即失效。</p>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  position: relative;
  display: grid;
  width: min(1000px, calc(100% - 40px));
  min-height: 100dvh;
  padding: max(40px, env(safe-area-inset-top)) 0 max(40px, env(safe-area-inset-bottom));
  margin: 0 auto;
  align-items: center;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: clamp(48px, 8vw, 96px);
}
.appearance-toggle { position: absolute; top: max(18px, env(safe-area-inset-top)); right: max(18px, env(safe-area-inset-right)); display: grid; width: 44px; height: 44px; padding: 0; place-items: center; border: 1px solid var(--separator); border-radius: 50%; color: var(--ink-soft); background: var(--surface-glass); box-shadow: 0 4px 14px var(--shadow-color); cursor: pointer; }
.appearance-toggle:hover { color: var(--accent-text); background: var(--fill); }
.auth-story { display: grid; min-width: 0; gap: var(--space-6); }
.brand-mark { width: 116px; aspect-ratio: 1; line-height: 0; }
.story-copy { max-width: 680px; }
.eyebrow, .auth-kicker {
  margin: 0 0 10px;
  color: var(--accent-text);
  font-size: var(--font-micro);
  font-weight: 650;
  letter-spacing: .02em;
}
.story-copy h1 {
  max-width: 560px;
  margin: 0;
  font-size: clamp(40px, 5vw, 54px);
  font-weight: 700;
  letter-spacing: -.05em;
  line-height: 1.08;
  text-wrap: balance;
}
.story-lead { max-width: 520px; margin: 18px 0 0; color: var(--ink-soft); font-size: var(--font-subtitle); line-height: 1.7; }
.node-discovery-disclosure { min-width: 0; }
.node-discovery-disclosure > summary { display: none; }
.auth-card { min-width: 0; width: 100%; padding: var(--space-8); border-radius: var(--radius-sheet); }
.auth-card-top h2 { margin: 0; font-size: 27px; letter-spacing: -.035em; }
.auth-card-top > p:last-child { margin: 10px 0 0; color: var(--ink-soft); line-height: 1.55; }
.mode-switch { position: relative; display: grid; height: var(--control-height); padding: var(--space-1); margin: 24px 0 22px; grid-template-columns: 1fr 1fr; border-radius: var(--radius-control); background: var(--hover-strong); }
.mode-switch button { position: relative; z-index: 1; border: 0; border-radius: var(--radius-sm); color: var(--ink-soft); font-size: var(--font-body); font-weight: 700; background: none; cursor: pointer; }
.mode-switch button[aria-selected="true"] { color: var(--ink); }
.mode-lens { position: absolute; top: 4px; left: 4px; width: calc(50% - 4px); height: 36px; border: 1px solid var(--glass-border); border-radius: var(--radius-control); background: var(--surface-glass); box-shadow: 0 2px 8px var(--shadow-color), inset 0 1px 0 var(--highlight); transition: transform 240ms var(--ease-liquid); }
.mode-lens--right { transform: translateX(100%); }
.registration-policy { margin: 22px 0 18px; padding: 11px 13px; border-radius: var(--radius-control); color: var(--ink-soft); font-size: var(--font-caption); background: var(--active); }
.auth-form { display: grid; gap: var(--space-4); }
.field-group { display: grid; min-width: 0; gap: var(--space-2); }
.field-group > span { color: var(--ink-soft); font-size: var(--font-caption); font-weight: 600; }
.form-error { margin: -4px 0 0; color: var(--danger); font-size: var(--font-body-sm); line-height: var(--line-body); overflow-wrap: anywhere; }
.submit-button { display: flex; width: 100%; margin-top: var(--space-1); align-items: center; justify-content: center; gap: var(--space-2); }
.privacy-note { margin: 22px 0 0; color: var(--ink-faint); font-size: var(--font-micro); line-height: 1.6; text-align: center; }
.auth-page--auto { place-items: center; grid-template-columns: 1fr; }
.auto-login-state { display: grid; justify-items: center; gap: var(--space-3); }
.auto-spinner { width: 28px; height: 28px; border: 2px solid var(--active); border-top-color: var(--blue); border-radius: 50%; animation: auth-spin .8s linear infinite; }
.auto-login-state strong { font-size: var(--font-body); color: var(--ink-soft); }
@keyframes auth-spin { to { transform: rotate(360deg); } }
@media (max-width: 860px) {
  .auth-page { width: min(520px, calc(100% - 28px)); grid-template-columns: minmax(0, 1fr); gap: 28px; padding: max(26px, env(safe-area-inset-top)) 0 max(26px, env(safe-area-inset-bottom)); }
  .auth-story { gap: var(--space-4); }
  .story-copy h1 { font-size: clamp(34px, 10vw, 44px); }
  .story-lead { margin-top: var(--space-3); font-size: var(--font-body); }
}
@media (max-width: 520px) {
  .auth-card { padding: var(--space-6) var(--space-5); }
  .brand-mark { width: 104px; }
}

@media (max-width: 860px) {
  .auth-page {
    width: 100%;
    min-height: var(--app-viewport-height, 100dvh);
    padding: max(18px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) max(22px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
    align-content: start;
    gap: 16px;
    overflow-y: auto;
    overscroll-behavior: contain;
    scroll-padding-bottom: max(22px, env(safe-area-inset-bottom));
  }
  .appearance-toggle { top: max(12px, env(safe-area-inset-top)); right: max(12px, env(safe-area-inset-right)); }
  .auth-story { min-height: 58px; gap: 10px; }
  .brand-mark { width: 58px; }
  .story-copy { display: none; }
  .node-discovery-disclosure { border: 1px solid var(--separator); border-radius: var(--radius-control); background: var(--surface-tint); }
  .node-discovery-disclosure > summary { display: flex; min-height: 44px; padding: 0 14px; align-items: center; justify-content: space-between; color: var(--ink-soft); font-size: var(--font-caption); font-weight: 650; cursor: pointer; list-style: none; }
  .node-discovery-disclosure > summary::-webkit-details-marker { display: none; }
  .node-discovery-disclosure[open] > summary .ui-icon { transform: rotate(90deg); }
  .auth-card { padding: 20px; border-radius: 22px; }
  .auth-card-top h2 { font-size: 24px; }
  .auth-card-top > p:last-child { margin-top: 6px; }
  .mode-switch { margin: 18px 0; }
  .registration-policy { margin: 18px 0 14px; }
  .auth-form { gap: 14px; }
  .privacy-note { margin-top: 16px; }
}
</style>
