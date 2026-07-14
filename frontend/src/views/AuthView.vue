<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { ApiError } from '../services/api'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'

const auth = useAuth()
const toast = useToast()
const mode = shallowRef<'login' | 'register'>('login')
const username = shallowRef('')
const password = shallowRef('')
const nickname = shallowRef('')
const error = shallowRef('')

const heading = computed(() => mode.value === 'login' ? '回到对话' : '创建你的空间')
const submitLabel = computed(() => {
  if (auth.loading.value) return mode.value === 'login' ? '正在连接…' : '正在创建…'
  return mode.value === 'login' ? '登录 LanChat' : '创建并进入'
})

function switchMode(next: 'login' | 'register'): void {
  mode.value = next
  error.value = ''
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
      window.location.assign('/chat')
    } else {
      await auth.register(cleanUsername, password.value, cleanNickname)
      toast.push('账号已创建', 'success', 1200)
      window.location.assign('/welcome')
    }
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '操作失败，请稍后重试'
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story" aria-label="LanChat 简介">
      <div class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 48 48" fill="none">
          <path d="M13 14.5h22a5 5 0 0 1 5 5v9a5 5 0 0 1-5 5H24l-9.5 7v-7H13a5 5 0 0 1-5-5v-9a5 5 0 0 1 5-5Z" fill="currentColor" />
          <path d="M17 23h14M17 28h9" stroke="white" stroke-width="2.5" stroke-linecap="round" />
        </svg>
      </div>

      <div class="story-copy">
        <p class="eyebrow">LAN / LOCAL / LIVE</p>
        <h1>让同一网络里的沟通，像水一样自然。</h1>
        <p class="story-lead">消息、文件和群组都停留在一个安静的工作面上。打开页面，就能继续刚才的对话。</p>
      </div>

    </section>

    <section class="auth-card glass-surface">
      <div class="auth-card-top">
        <p class="auth-kicker">LanChat</p>
        <h2>{{ heading }}</h2>
        <p>{{ mode === 'login' ? '输入账号，继续未完成的对话。' : '只需三个信息，就能开始聊天。' }}</p>
      </div>

      <div class="mode-switch" role="tablist" aria-label="登录方式">
        <span class="mode-lens" :class="{ 'mode-lens--right': mode === 'register' }" />
        <button type="button" role="tab" :aria-selected="mode === 'login'" @click="switchMode('login')">登录</button>
        <button type="button" role="tab" :aria-selected="mode === 'register'" @click="switchMode('register')">注册</button>
      </div>

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
            v-model="password"
            class="field"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            maxlength="20"
            placeholder="8–20 位字母与数字"
          />
        </label>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="primary-button submit-button" type="submit" :disabled="auth.loading.value">
          {{ submitLabel }}
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="m7 4 6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </form>

      <p class="privacy-note">访问令牌只保存在当前浏览器；退出登录后立即失效。</p>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  display: grid;
  width: min(1180px, calc(100% - 40px));
  min-height: 100dvh;
  padding: 46px 0;
  margin: 0 auto;
  align-items: center;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 0.8fr);
  gap: clamp(42px, 7vw, 104px);
}

.auth-story { display: grid; gap: 34px; }

.brand-mark {
  position: relative;
  display: grid;
  width: 72px;
  height: 72px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.86);
  border-radius: 25px 25px 25px 12px;
  color: var(--blue);
  background: linear-gradient(145deg, rgba(255,255,255,.88), rgba(255,255,255,.4));
  box-shadow: inset 0 1px 0 #fff, 0 20px 34px rgba(10, 132, 255, 0.16);
  transform: rotate(-3deg);
}

.brand-mark svg { width: 48px; }
.brand-glint {
  position: absolute;
  top: 9px;
  left: 15px;
  width: 20px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  filter: blur(2px);
}

.story-copy { max-width: 680px; }
.eyebrow,
.auth-kicker {
  margin: 0 0 12px;
  color: #2878c9;
  font-family: "SF Mono", "Menlo", monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.story-copy h1 {
  max-width: 650px;
  margin: 0;
  font-family: "Avenir Next", "PingFang SC", sans-serif;
  font-size: clamp(42px, 5vw, 72px);
  font-weight: 650;
  letter-spacing: -0.055em;
  line-height: 1.06;
}

.story-lead {
  max-width: 580px;
  margin: 24px 0 0;
  color: var(--ink-soft);
  font-size: 17px;
  line-height: 1.8;
}

.signal-demo {
  display: flex;
  width: min(520px, 100%);
  min-height: 132px;
  padding: 20px 24px;
  align-items: center;
  gap: 24px;
  border-radius: 28px 28px 28px 12px;
}

.signal-orbit { position: relative; width: 88px; height: 88px; flex: 0 0 auto; }
.signal-core {
  position: absolute;
  z-index: 2;
  inset: 29px;
  border: 5px solid rgba(255,255,255,.85);
  border-radius: 50%;
  background: linear-gradient(145deg, var(--cyan), var(--blue));
  box-shadow: 0 6px 16px rgba(10, 132, 255, 0.34);
}
.orbit { position: absolute; inset: 14px; border: 1px solid rgba(10, 132, 255, 0.22); border-radius: 50%; }
.orbit-two { inset: 0; border-color: rgba(118, 103, 245, 0.15); }
.orbit-node { position: absolute; width: 11px; height: 11px; border: 3px solid white; border-radius: 50%; background: var(--violet); box-shadow: 0 4px 12px rgba(72, 69, 180, .25); }
.node-one { top: 13px; right: 12px; }
.node-two { bottom: 16px; left: 8px; background: var(--green); }

.signal-copy { display: grid; gap: 5px; }
.signal-copy strong { font-size: 16px; }
.signal-copy small { color: var(--ink-soft); line-height: 1.5; }
.signal-status { color: #248c43; font-size: 12px; font-weight: 700; }
.signal-status i { display: inline-block; width: 8px; height: 8px; margin-right: 6px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 5px rgba(48,209,88,.12); }

.auth-card {
  width: 100%;
  padding: clamp(28px, 4vw, 42px);
  border-radius: 36px 36px 36px 18px;
}
.auth-card-top h2 { margin: 0; font-size: 30px; letter-spacing: -0.035em; }
.auth-card-top > p:last-child { margin: 10px 0 0; color: var(--ink-soft); line-height: 1.55; }

.mode-switch {
  position: relative;
  display: grid;
  height: 48px;
  padding: 4px;
  margin: 28px 0 24px;
  grid-template-columns: 1fr 1fr;
  border: 1px solid rgba(140, 167, 195, 0.18);
  border-radius: 17px;
  background: rgba(205, 223, 241, 0.32);
}
.mode-switch button { position: relative; z-index: 1; border: 0; color: var(--ink-soft); font-weight: 700; background: none; cursor: pointer; }
.mode-switch button[aria-selected="true"] { color: var(--ink); }
.mode-lens {
  position: absolute;
  top: 4px;
  left: 4px;
  width: calc(50% - 4px);
  height: 38px;
  border: 1px solid rgba(255,255,255,.92);
  border-radius: 13px 16px 16px 13px;
  background: linear-gradient(145deg, rgba(255,255,255,.94), rgba(255,255,255,.55));
  box-shadow: 0 8px 16px rgba(52, 88, 125, .1), inset 0 1px 0 white;
  transition: transform 360ms var(--ease-liquid), border-radius 360ms var(--ease-liquid);
}
.mode-lens--right { transform: translateX(100%); border-radius: 16px 13px 13px 16px; }

.auth-form { display: grid; gap: 18px; }
.field-group { display: grid; gap: 8px; }
.field-group > span { color: #35506e; font-size: 13px; font-weight: 700; }
.form-error { margin: -4px 0 0; color: #c93f49; font-size: 13px; line-height: 1.5; }
.submit-button { display: flex; width: 100%; margin-top: 4px; align-items: center; justify-content: center; gap: 8px; }
.submit-button svg { width: 18px; }
.privacy-note { margin: 22px 0 0; color: #6f8297; font-size: 11px; line-height: 1.6; text-align: center; }

@media (max-width: 860px) {
  .auth-page { grid-template-columns: 1fr; padding: 28px 0 44px; }
  .auth-story { gap: 22px; }
  .story-copy h1 { font-size: clamp(38px, 11vw, 58px); }
  .story-lead { font-size: 15px; }
  .signal-demo { display: none; }
  .auth-card { max-width: 520px; margin: 0 auto; }
}

@media (max-width: 520px) {
  .auth-page { width: min(100% - 24px, 460px); }
  .auth-card { padding: 25px 20px; border-radius: 28px 28px 28px 15px; }
  .brand-mark { width: 58px; height: 58px; border-radius: 20px 20px 20px 10px; }
  .brand-mark svg { width: 39px; }
}

.auth-page {
  width: min(1000px, calc(100% - 40px));
  padding: 40px 0;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: clamp(48px, 8vw, 96px);
}
.auth-story { gap: 24px; }
.brand-mark {
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 17px;
  color: var(--blue);
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 6px 18px rgba(29, 29, 31, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.94);
  transform: none;
  backdrop-filter: blur(16px) saturate(150%);
}
.brand-mark svg { width: 38px; }
.eyebrow,
.auth-kicker {
  margin-bottom: 10px;
  color: var(--blue);
  font-family: inherit;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.02em;
}
.story-copy h1 {
  max-width: 560px;
  font-family: inherit;
  font-size: clamp(40px, 5vw, 54px);
  font-weight: 700;
  letter-spacing: -0.05em;
  line-height: 1.08;
}
.story-lead { max-width: 520px; margin-top: 18px; color: var(--ink-soft); font-size: 16px; line-height: 1.7; }
.auth-card {
  padding: 32px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.76);
  box-shadow: 0 14px 42px rgba(29, 29, 31, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.94);
}
.auth-card-top h2 { font-size: 27px; }
.mode-switch {
  height: 44px;
  margin: 24px 0 22px;
  border: 0;
  border-radius: 13px;
  background: rgba(118, 118, 128, 0.1);
}
.mode-lens {
  height: 36px;
  border-color: rgba(255, 255, 255, 0.84);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 2px 8px rgba(29, 29, 31, 0.08), inset 0 1px 0 #fff;
  backdrop-filter: blur(16px) saturate(150%);
}
.mode-lens--right { border-radius: 10px; }
.auth-form { gap: 16px; }
.field-group > span { color: var(--ink-soft); font-size: 12px; font-weight: 600; }
.privacy-note { color: var(--ink-faint); }

@media (max-width: 860px) {
  .auth-page { width: min(520px, calc(100% - 28px)); grid-template-columns: 1fr; gap: 28px; padding: 26px 0; }
  .auth-story { gap: 16px; }
  .story-copy h1 { font-size: clamp(34px, 10vw, 44px); }
  .story-lead { margin-top: 12px; font-size: 14px; }
}
@media (max-width: 520px) {
  .auth-card { padding: 24px 20px; border-radius: 22px; }
  .brand-mark { width: 50px; height: 50px; border-radius: 15px; }
  .brand-mark svg { width: 34px; }
}
</style>
