<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import type { DeviceLogin } from '../../types'
import { api } from '../../services/api'
import { useToast } from '../../composables/useToast'
import { formatMessageTime } from '../../utils/format'
import { nativeBridge } from '../../platform/nativeBridge'
import UiIcon, { type IconName } from '../base/UiIcon.vue'

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  currentDeviceLoggedOut: []
}>()
const toast = useToast()
const devices = shallowRef<DeviceLogin[]>([])
const loading = shallowRef(false)
const busyId = shallowRef<number | null>(null)

watch(() => props.open, async (open) => {
  if (!open) return
  loading.value = true
  try {
    devices.value = await api.user.devices()
  } catch {
    toast.push('获取设备列表失败', 'danger')
  } finally {
    loading.value = false
  }
}, { immediate: true })

async function kickDevice(device: DeviceLogin): Promise<void> {
  const prompt = device.current
    ? '确定下线当前设备？下线后将退出登录并返回首页。'
    : '确定下线该设备？'
  if (!await nativeBridge.confirm(prompt, {
    title: device.current ? '下线当前设备' : '下线登录设备',
    kind: 'warning',
    okLabel: '确认下线',
    cancelLabel: '取消',
  })) return
  busyId.value = device.id
  try {
    await api.user.logoutDevice(device.id)
    if (device.current) {
      emit('currentDeviceLoggedOut')
      return
    }
    devices.value = devices.value.filter(item => item.id !== device.id)
    toast.push('设备已下线', 'success')
  } catch {
    toast.push('操作失败', 'danger')
  } finally {
    busyId.value = null
  }
}

function deviceIcon(type: string): IconName {
  switch (type?.toLowerCase()) {
    case 'web': return 'globe'
    case 'android': return 'smartphone'
    case 'ios': return 'smartphone'
    default: return 'monitor'
  }
}

function shortDeviceName(name: string): string {
  if (!name) return '未知设备'
  if (name.length > 50) return name.slice(0, 50) + '…'
  return name
}
</script>

<template>
  <div v-if="open" class="modal-backdrop apple-modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="device-sheet apple-modal-surface" role="dialog" aria-modal="true" aria-labelledby="device-title">
      <button class="close-button apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <h2 id="device-title">登录设备</h2>

      <div v-if="loading" class="device-loading">
        <span class="spinner" />
        <p>正在加载…</p>
      </div>

      <div v-else-if="devices.length === 0" class="device-empty">
        <p>暂无登录设备信息</p>
      </div>

      <div v-else class="device-list">
        <div v-for="device in devices" :key="device.id" class="device-item">
          <UiIcon class="device-icon" :name="deviceIcon(device.deviceType)" :size="24" />
          <div class="device-info">
            <div class="device-heading">
              <strong>{{ device.deviceType || '未知' }}</strong>
              <span v-if="device.current">本设备</span>
            </div>
            <small>{{ shortDeviceName(device.deviceName) }}</small>
            <span class="device-time">登录于 {{ formatMessageTime(device.loginTime) }}</span>
          </div>
          <button
            class="kick-button"
            type="button"
            :disabled="busyId === device.id"
            @click="kickDevice(device)"
          >{{ busyId === device.id ? '…' : '下线' }}</button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 110;
  inset: 0;
  display: grid;
  padding: var(--space-5);
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.device-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  max-height: calc(100dvh - 40px);
  padding: 28px 24px 22px;
  gap: 2px;
  border-radius: var(--radius-sheet);
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow-y: auto;
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
.close-button .ui-icon { width: 16px; }

.device-sheet h2 { margin: 0; font-size: var(--font-title); letter-spacing: -0.02em; }

.device-loading {
  display: grid;
  min-height: 120px;
  place-items: center;
  align-content: center;
  gap: 10px;
}
.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid color-mix(in srgb, var(--blue) 16%, transparent);
  border-top-color: var(--accent-text);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.device-empty { padding: 28px 0; color: var(--ink-faint); font-size: var(--font-body-sm); text-align: center; }

.device-list { display: grid; margin-top: 14px; gap: var(--space-1); }

.device-item {
  display: flex;
  padding: var(--space-3);
  align-items: center;
  gap: var(--space-3);
  border-radius: var(--radius-control);
  background: var(--fill);
  transition: background-color 150ms ease;
}

.device-icon { color: var(--ink-soft); flex-shrink: 0; }
.device-info { display: grid; min-width: 0; flex: 1; gap: 2px; }
.device-heading { display: flex; align-items: center; gap: 7px; }
.device-heading strong { font-size: var(--font-body-sm); font-weight: 600; }
.device-heading span { padding: 2px 6px; border-radius: var(--radius-pill); color: var(--accent-text); font-size: var(--font-micro); font-weight: 700; background: var(--active); }
.device-info small { overflow: hidden; color: var(--ink-soft); font-size: var(--font-micro); text-overflow: ellipsis; white-space: nowrap; }
.device-time { color: var(--ink-faint); font-size: var(--font-caption); }

.kick-button {
  min-width: 48px;
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--danger);
  font-size: var(--font-micro);
  font-weight: 700;
  background: color-mix(in srgb, var(--danger-bg) 8%, transparent);
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 150ms ease;
}
.kick-button:hover { background: color-mix(in srgb, var(--danger-bg) 14%, transparent); }
.kick-button:disabled { opacity: 0.5; cursor: wait; }
</style>
