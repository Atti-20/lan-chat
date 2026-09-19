<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import UiIcon from '../base/UiIcon.vue'
import { initializeMobileNotification, mobileNotificationPermission } from '../../platform/mobileNotifications'

const permission = shallowRef<Awaited<ReturnType<typeof mobileNotificationPermission>>>('unavailable')
const busy = shallowRef(false)
const description = computed(() => {
  if (busy.value) return '正在检查系统权限…'
  if (permission.value === 'granted') return '已允许系统通知'
  if (permission.value === 'denied') return '请在系统设置中允许 MeshX 通知'
  if (permission.value === 'prompt') return '点击允许新消息提醒'
  return '暂时无法读取通知权限，点击重试'
})

async function refresh(): Promise<void> {
  permission.value = await mobileNotificationPermission()
}

async function enable(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    // Ask while the user is foregrounded, not after iOS has suspended the app.
    await initializeMobileNotification({ requestPermission: true })
    await refresh()
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void refresh()
  document.addEventListener('visibilitychange', refresh)
})
onBeforeUnmount(() => document.removeEventListener('visibilitychange', refresh))
</script>

<template>
  <button class="notification-setting" type="button" :disabled="busy || permission === 'denied'" @click="enable">
    <UiIcon name="bell" :size="18" />
    <span><strong>系统通知</strong><small role="status">{{ description }}</small></span>
    <UiIcon v-if="permission !== 'denied'" :name="permission === 'granted' ? 'check' : 'arrow-right'" :size="16" />
  </button>
</template>

<style scoped>
.notification-setting { display: grid; width: 100%; min-height: 56px; padding: 9px 10px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--space-3); border: 0; border-radius: var(--radius-md); color: var(--ink); text-align: left; background: transparent; cursor: pointer; }
.notification-setting:hover { background: var(--fill); }
.notification-setting:disabled { cursor: wait; opacity: .7; }
.notification-setting > .ui-icon:first-child { color: var(--accent-text); }
.notification-setting span { display: grid; gap: 3px; }
.notification-setting strong { font-size: var(--font-body-sm); }
.notification-setting small { color: var(--ink-faint); font-size: var(--font-caption); }
</style>
