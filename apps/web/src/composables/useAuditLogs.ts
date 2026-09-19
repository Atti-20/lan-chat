import { onBeforeUnmount, readonly, shallowRef } from 'vue'
import { api } from '../services/api'
import type { AuditEvent } from '../types'

export function useAuditLogs() {
  const events = shallowRef<AuditEvent[]>([])
  const loading = shallowRef(false)
  const error = shallowRef('')
  let generation = 0

  async function load(action = '', outcome = ''): Promise<void> {
    const current = ++generation
    loading.value = true
    error.value = ''
    events.value = []
    try {
      const result = await api.admin.auditEvents({ action: action.trim(), outcome })
      if (current === generation) events.value = result
    } catch (cause) {
      if (current === generation) error.value = cause instanceof Error ? cause.message : '操作审计加载失败'
    } finally {
      if (current === generation) loading.value = false
    }
  }

  onBeforeUnmount(() => { generation += 1 })
  return { events: readonly(events), loading: readonly(loading), error: readonly(error), load }
}
