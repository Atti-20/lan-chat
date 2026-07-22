<script setup lang="ts">
import type { User } from '../../types'
import { useTheme } from '../../composables/useTheme'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'

defineProps<{
  open: boolean
  user: User
  desktop?: boolean
  connectionSummary?: string
}>()

const emit = defineEmits<{
  close: []
  openProfileEditor: []
  openDevices: []
  openPassword: []
  openFileTransferSettings: []
  openDesktopSettings: []
  logout: []
}>()

const { mode: themeMode, toggleWithReveal: toggleTheme } = useTheme()
</script>

<template>
  <div v-if="open" class="modal-backdrop detail-backdrop apple-modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="personal-sheet detail-panel apple-modal-surface" role="dialog" aria-modal="true" aria-labelledby="personal-profile-title">
      <header>
        <div>
          <p>MY PROFILE</p>
          <h2 id="personal-profile-title">个人资料</h2>
        </div>
        <button class="apple-modal-close" type="button" aria-label="关闭" @click="emit('close')"><UiIcon name="close" :size="17" /></button>
      </header>

      <div class="personal-body">
        <UserAvatar :name="user.nickname || user.username" :avatar="user.avatar" :size="88" online />
        <div class="identity">
          <strong>{{ user.nickname || user.username }}</strong>
          <span>@{{ user.username }}</span>
        </div>
        <div v-if="connectionSummary" class="connection-summary" aria-label="连接状态">
          <UiIcon name="activity" :size="17" />
          <span><strong>连接状态</strong><small>{{ connectionSummary }}</small></span>
        </div>
        <nav class="settings-list" aria-label="账户设置">
          <button type="button" @click="emit('openProfileEditor')"><UiIcon name="edit" :size="18" /><span><strong>修改资料</strong></span><UiIcon name="arrow-right" :size="16" /></button>
          <button type="button" @click="toggleTheme"><UiIcon :name="themeMode === 'dark' ? 'sun' : 'moon'" :size="18" /><span><strong>{{ themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式' }}</strong></span></button>
          <button type="button" @click="emit('openDevices')"><UiIcon name="monitor" :size="18" /><span><strong>登录设备管理</strong></span><UiIcon name="arrow-right" :size="16" /></button>
          <button type="button" @click="emit('openPassword')"><UiIcon name="lock" :size="18" /><span><strong>修改密码</strong></span><UiIcon name="arrow-right" :size="16" /></button>
          <button type="button" @click="emit('openFileTransferSettings')"><UiIcon name="file" :size="18" /><span><strong>文件传输方式</strong></span><UiIcon name="arrow-right" :size="16" /></button>
          <button v-if="desktop" type="button" @click="emit('openDesktopSettings')"><UiIcon name="download" :size="18" /><span><strong>桌面端设置与更新</strong></span><UiIcon name="arrow-right" :size="16" /></button>
        </nav>
        <button class="logout-button" type="button" @click="emit('logout')">退出登录</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; padding: 20px; place-items: center; }
.personal-sheet { width: min(100%, 430px); max-height: calc(100dvh - 40px); overflow-y: auto; border-radius: 22px; box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft); }
header { display: flex; padding: 20px 22px 16px; align-items: flex-start; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--separator); }
header p { margin: 0 0 3px; color: var(--blue); font-size: 9px; font-weight: 750; letter-spacing: .12em; }
header h2 { margin: 0; font-size: 22px; letter-spacing: -.03em; }
header button { display: grid; width: 40px; height: 40px; padding: 0; place-items: center; border: 0; border-radius: 50%; color: var(--ink-soft); background: var(--fill); cursor: pointer; }
header button:hover { background: var(--button-hover); }
.personal-body { display: grid; padding: 28px 24px 24px; justify-items: center; gap: 10px; }
.identity { display: grid; justify-items: center; gap: 4px; }
.identity strong { font-size: 20px; letter-spacing: -.02em; }
.identity span { color: var(--ink-faint); font-size: 12px; }
.connection-summary { display: grid; width: 100%; min-height: 54px; padding: 10px 12px; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 12px; border-radius: 14px; background: var(--fill); }
.connection-summary > .ui-icon { color: var(--green); }
.connection-summary span { display: grid; min-width: 0; gap: 2px; }
.connection-summary strong { font-size: 12px; }
.connection-summary small { overflow: hidden; color: var(--ink-faint); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.settings-list { display: grid; width: 100%; gap: 2px; }
.settings-list button { display: grid; width: 100%; min-height: 48px; padding: 9px 10px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px; border: 0; border-radius: 14px; color: var(--ink); text-align: left; background: transparent; cursor: pointer; transition: background-color 160ms ease, transform 160ms var(--ease-liquid); }
.settings-list button:hover { background: var(--fill); }
.settings-list button:active { transform: scale(.988); }
.settings-list button > .ui-icon:first-child { color: var(--blue); }
.settings-list button > .ui-icon:last-child { color: var(--ink-faint); }
.settings-list span { display: grid; min-width: 0; }
.settings-list strong { font-size: 13px; }
.logout-button { width: 100%; min-height: 44px; margin-top: 4px; border: 0; border-radius: 12px; color: var(--coral); font-size: 13px; font-weight: 700; background: color-mix(in srgb, var(--coral) 8%, transparent); cursor: pointer; }
.logout-button:hover { background: color-mix(in srgb, var(--coral) 13%, transparent); }
@media (max-width: 760px) { .modal-backdrop { padding: 16px; align-items: end; } .personal-sheet { width: 100%; border-radius: 22px; } }
</style>
