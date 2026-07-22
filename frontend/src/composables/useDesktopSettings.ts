import { readonly, shallowRef } from 'vue'
import {
  nativeBridge,
  type RuntimeInfo,
  type UpdateResult,
} from '../platform/nativeBridge'

export function useDesktopSettings() {
  const runtimeInfo = shallowRef<RuntimeInfo | null>(null)
  const autostartEnabled = shallowRef(false)
  const update = shallowRef<UpdateResult | null>(null)
  const loading = shallowRef(false)
  const error = shallowRef('')

  async function refresh(): Promise<void> {
    if (nativeBridge.runtime() !== 'tauri' || loading.value) return
    loading.value = true
    error.value = ''
    try {
      const [runtime, enabled] = await Promise.all([
        nativeBridge.runtimeInfo(),
        nativeBridge.autostartEnabled(),
      ])
      runtimeInfo.value = runtime
      autostartEnabled.value = enabled
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '桌面设置暂时不可用'
    } finally {
      loading.value = false
    }
  }

  async function setAutostart(enabled: boolean): Promise<void> {
    const previous = autostartEnabled.value
    autostartEnabled.value = enabled
    error.value = ''
    try {
      await nativeBridge.setAutostart(enabled)
    } catch (cause) {
      autostartEnabled.value = previous
      error.value = cause instanceof Error ? cause.message : '开机自启设置失败'
    }
  }

  async function checkForUpdate(install = false): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      update.value = await nativeBridge.checkForUpdate(install)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '更新检查失败'
    } finally {
      loading.value = false
    }
  }

  async function sendTestNotification(): Promise<void> {
    error.value = ''
    try {
      await nativeBridge.notify({
        title: 'MeshX 通知已启用',
        body: '收到新消息时，桌面端会在后台提醒你。',
      })
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '系统通知授权失败'
    }
  }

  return {
    runtimeInfo: readonly(runtimeInfo),
    autostartEnabled: readonly(autostartEnabled),
    update: readonly(update),
    loading: readonly(loading),
    error: readonly(error),
    refresh,
    setAutostart,
    checkForUpdate,
    sendTestNotification,
  }
}
