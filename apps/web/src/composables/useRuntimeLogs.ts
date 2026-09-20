import { readonly, shallowRef } from 'vue'
import { ApiError, api } from '../services/api'
import type { RuntimeLogLevelFilter, RuntimeLogSnapshot } from '../types'
import { useToast } from './useToast'

interface RuntimeLogQuery {
  limit?: number
  level?: RuntimeLogLevelFilter
  keyword?: string
}

export function useRuntimeLogs() {
  const toast = useToast()
  const snapshot = shallowRef<RuntimeLogSnapshot | null>(null)
  const loading = shallowRef(false)
  const exporting = shallowRef(false)
  const error = shallowRef<string | null>(null)
  let requestSequence = 0

  async function load(query: RuntimeLogQuery = {}): Promise<void> {
    const sequence = ++requestSequence
    loading.value = true
    error.value = null
    try {
      const result = await api.admin.runtimeLogs(query)
      if (sequence === requestSequence) snapshot.value = result
    } catch (cause) {
      if (sequence === requestSequence) error.value = errorMessage(cause, '运行日志加载失败')
    } finally {
      if (sequence === requestSequence) loading.value = false
    }
  }

  async function exportLog(): Promise<void> {
    if (exporting.value) return
    exporting.value = true
    try {
      const { blob, fileName } = await api.admin.exportRuntimeLog()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = fileName
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
      toast.push('运行日志已导出', 'success')
    } catch (cause) {
      toast.push(errorMessage(cause, '运行日志导出失败'), 'danger')
    } finally {
      exporting.value = false
    }
  }

  function errorMessage(cause: unknown, fallback: string): string {
    return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback
  }

  return {
    snapshot: readonly(snapshot),
    loading: readonly(loading),
    exporting: readonly(exporting),
    error: readonly(error),
    load,
    exportLog,
  }
}
