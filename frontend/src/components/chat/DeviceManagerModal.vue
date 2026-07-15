<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import type { DeviceLogin } from '../../types'
import { api } from '../../services/api'
import { useToast } from '../../composables/useToast'
import { formatMessageTime } from '../../utils/format'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{ close: [] }>()
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

async function kickDevice(deviceId: number): Promise<void> {
  if (!window.confirm('确定下线该设备？')) return
  busyId.value = deviceId
  try {
    await api.user.logoutDevice(deviceId)
    devices.value = devices.value.filter(d => d.id !== deviceId)
    toast.push('设备已下线', 'success')
  } catch {
    toast.push('操作失败', 'danger')
  } finally {
    busyId.value = null
  }
}

function deviceIcon(type: string): string {
  switch (type?.toLowerCase()) {
    case 'web': return '🌐'
    case 'android': return '📱'
    case 'ios': return '📱'
    case 'desktop': return '🖥️'
    default: return '💻'
  }
}

function shortDeviceName(name: string): string {
  if (!name) return '未知设备'
  if (name.length > 50) return name.slice(0, 50) + '…'
  return name
}
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="device-sheet" role="dialog" aria-modal="true" aria-labelledby="device-title">
      <button class="close-button" type="button" aria-label="关闭" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <h2 id="device-title">登录设备</h2>
      <p class="device-desc">管理当前登录到你账号的设备</p>

      <div v-if="loading" class="device-loading">
        <span class="spinner" />
        <p>正在加载…</p>
      </div>

      <div v-else-if="devices.length === 0" class="device-empty">
        <p>暂无登录设备信息</p>
      </div>

      <div v-else class="device-list">
        <div v-for="device in devices" :key="device.id" class="device-item">
          <span class="device-icon">{{ deviceIcon(device.deviceType) }}</span>
          <div class="device-info">
            <strong>{{ device.deviceType || '未知' }}</strong>
            <small>{{ shortDeviceName(device.deviceName) }}</small>
            <span class="device-time">登录于 {{ formatMessageTime(device.loginTime) }}</span>
          </div>
          <button
            class="kick-button"
            type="button"
            :disabled="busyId === device.id"
            @click="kickDevice(device.id)"
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
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(7px);
  -webkit-backdrop-filter: blur(7px);
}

.device-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  max-height: calc(100dvh - 40px);
  padding: 28px 24px 22px;
  gap: 2px;
  border-radius: 22px;
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

.device-sheet h2 { margin: 0; font-size: 20px; letter-spacing: -0.02em; }
.device-desc { margin: 4px 0 14px; color: var(--ink-soft); font-size: 12px; }

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
  border-top-color: var(--blue);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.device-empty { padding: 28px 0; color: var(--ink-faint); font-size: 13px; text-align: center; }

.device-list { display: grid; gap: 4px; }

.device-item {
  display: flex;
  padding: 12px;
  align-items: center;
  gap: 12px;
  border-radius: 13px;
  background: var(--fill);
  transition: background-color 150ms ease;
}

.device-icon { font-size: 22px; flex-shrink: 0; }
.device-info { display: grid; min-width: 0; flex: 1; gap: 2px; }
.device-info strong { font-size: 13px; font-weight: 600; }
.device-info small { overflow: hidden; color: var(--ink-soft); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.device-time { color: var(--ink-faint); font-size: 10px; }

.kick-button {
  min-width: 48px;
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  color: var(--coral);
  font-size: 11px;
  font-weight: 700;
  background: color-mix(in srgb, var(--coral) 8%, transparent);
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 150ms ease;
}
.kick-button:hover { background: color-mix(in srgb, var(--coral) 14%, transparent); }
.kick-button:disabled { opacity: 0.5; cursor: wait; }
</style>
