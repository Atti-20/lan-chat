import { computed, onBeforeUnmount, readonly, ref, shallowRef } from 'vue'
import { api } from '../services/api'
import { subscribeRealtimeEvents } from '../services/realtimeEvents'
import type {
  BroadcastCreatePayload,
  BroadcastCompletePayload,
  BroadcastDetail,
  BroadcastStatistics,
  EmergencyBroadcast,
  WsEnvelope,
} from '../types'

interface UseBroadcastsOptions {
  canViewAllStatistics?: () => boolean
}

export function useBroadcasts(options: UseBroadcastsOptions = {}) {
  const broadcasts = ref<EmergencyBroadcast[]>([])
  const pending = ref<EmergencyBroadcast[]>([])
  const selectedId = shallowRef<number | null>(null)
  const selected = shallowRef<BroadcastDetail | null>(null)
  const statistics = shallowRef<BroadcastStatistics | null>(null)
  const emergencyAlert = shallowRef<BroadcastDetail | null>(null)
  const listLoading = shallowRef(false)
  const detailLoading = shallowRef(false)
  const saving = shallowRef(false)
  const cancelling = shallowRef(false)
  const deleting = shallowRef(false)
  let selectionVersion = 0
  const loading = computed(() => listLoading.value || detailLoading.value)

  const pendingIds = computed(() => new Set(pending.value.map((item) => item.id)))
  const pendingCount = computed(() => pending.value.length)
  const unsubscribe = subscribeRealtimeEvents(handleRealtimeEvent)
  onBeforeUnmount(unsubscribe)

  function canViewStatistics(detail = selected.value): boolean {
    return Boolean(
      detail?.createdByCurrentUser
      || options.canViewAllStatistics?.() === true,
    )
  }

  async function load(): Promise<void> {
    listLoading.value = true

    try {
      await refreshListsOnly()

      const urgent = pending.value.find(
          (item) => item.priority === 'EMERGENCY',
      )

      emergencyAlert.value = urgent
          ? await api.broadcasts.detail(urgent.id).catch(() => null)
          : null

      if (emergencyAlert.value?.receiver?.confirmedAt) {
        emergencyAlert.value = null
      }
    } finally {
      listLoading.value = false
    }
  }
  async function selectBroadcast(
      broadcastId: number,
  ): Promise<void> {
    // 重复点击当前已经打开的广播时，不重新请求。
    if (
        selectedId.value === broadcastId
        && selected.value?.broadcast.id === broadcastId
        && !detailLoading.value
    ) {
      return
    }

    const currentVersion = ++selectionVersion

    // 点击后立即选中左侧条目。
    selectedId.value = broadcastId

    // 切换广播时，不继续显示上一条广播详情。
    selected.value = null
    statistics.value = null
    detailLoading.value = true

    try {
      let detail = await api.broadcasts.detail(broadcastId)

      if (currentVersion !== selectionVersion) {
        return
      }

      if (detail.receiver && !detail.receiver.viewedAt) {
        // view 接口本身会返回更新后的 receiver，
        // 不需要再请求一次 detail。
        const receiver = await api.broadcasts.view(broadcastId)

        if (currentVersion !== selectionVersion) {
          return
        }

        detail = {
          ...detail,
          receiver,
        }

        // 只刷新待处理广播，不重新请求完整广播列表。
        const unresolved = await api.broadcasts.pending()
            .catch(() => null)

        if (
            currentVersion === selectionVersion
            && unresolved
        ) {
          pending.value = unresolved
        }
      }

      if (currentVersion !== selectionVersion) {
        return
      }

      selected.value = detail

      if (emergencyAlert.value?.broadcast.id === broadcastId) {
        emergencyAlert.value = null
      }

      if (canViewStatistics(detail)) {
        const nextStatistics = await api.broadcasts
            .stats(broadcastId)
            .catch(() => null)

        if (currentVersion === selectionVersion) {
          statistics.value = nextStatistics
        }
      }
    } catch (cause) {
      if (currentVersion === selectionVersion) {
        selectedId.value = null
        selected.value = null
        statistics.value = null
      }

      throw cause
    } finally {
      if (currentVersion === selectionVersion) {
        detailLoading.value = false
      }
    }
  }

  async function createBroadcast(payload: BroadcastCreatePayload): Promise<EmergencyBroadcast> {
    saving.value = true
    try {
      const created = await api.broadcasts.create(payload)
      await refreshListsOnly()
      await selectBroadcast(created.id)
      return created
    } finally {
      saving.value = false
    }
  }

  async function cancelBroadcast(
    broadcastId = selected.value?.broadcast.id,
  ): Promise<void> {
    if (!broadcastId) return
    cancelling.value = true
    try {
      await api.broadcasts.cancel(broadcastId)
      if (emergencyAlert.value?.broadcast.id === broadcastId) {
        emergencyAlert.value = null
      }
      if (selected.value?.broadcast.id === broadcastId) {
        selected.value = await api.broadcasts.detail(broadcastId)
        statistics.value = canViewStatistics()
          ? await api.broadcasts.stats(broadcastId)
          : null
      }
      await refreshListsOnly()
    } finally {
      cancelling.value = false
    }
  }

  async function deleteBroadcast(broadcastId = selected.value?.broadcast.id): Promise<void> {
    if (!broadcastId) return
    deleting.value = true
    try {
      await api.broadcasts.remove(broadcastId)
      if (emergencyAlert.value?.broadcast.id === broadcastId) emergencyAlert.value = null
      if (selected.value?.broadcast.id === broadcastId) clearSelection()
      await refreshListsOnly()
    } finally {
      deleting.value = false
    }
  }

  async function complete(payload: BroadcastCompletePayload,
                          broadcastId = selected.value?.broadcast.id): Promise<void> {
    if (!broadcastId) return
    await api.broadcasts.complete(broadcastId, payload)
    const refreshed = await api.broadcasts.detail(broadcastId)
    if (selected.value?.broadcast.id === broadcastId) selected.value = refreshed
    await refreshListsOnly()
    await refreshStatistics().catch(() => undefined)
  }

  async function confirm(
    status: string,
    broadcastId = selected.value?.broadcast.id,
  ): Promise<void> {
    if (!broadcastId) return
    await api.broadcasts.confirm(broadcastId, status)
    const refreshed = await api.broadcasts.detail(broadcastId)
    if (selected.value?.broadcast.id === broadcastId) selected.value = refreshed
    if (emergencyAlert.value?.broadcast.id === broadcastId) emergencyAlert.value = refreshed
    await refreshListsOnly()
    if (emergencyAlert.value?.receiver?.confirmedAt) emergencyAlert.value = null
  }

  async function refreshStatistics(): Promise<void> {
    const broadcastId = selected.value?.broadcast.id
    if (!broadcastId || !canViewStatistics()) return
    statistics.value = await api.broadcasts.stats(broadcastId)
  }

  function closeEmergencyAlert(): void {
    if (emergencyAlert.value?.broadcast.confirmationRequired
      && !emergencyAlert.value.receiver?.confirmedAt) return
    emergencyAlert.value = null
  }

  function clearSelection(): void {
    selectionVersion += 1
    selectedId.value = null
    selected.value = null
    statistics.value = null
    detailLoading.value = false
  }

  async function refreshListsOnly(): Promise<void> {
    const [visible, unresolved] = await Promise.all([
      api.broadcasts.list(),
      api.broadcasts.pending(),
    ])
    broadcasts.value = visible || []
    pending.value = unresolved || []
  }

  async function handleRealtimeEvent(envelope: WsEnvelope): Promise<void> {
    if (envelope.event !== 'BROADCAST' && envelope.event !== 'BROADCAST_UPDATED') return

    await refreshListsOnly().catch(() => undefined)
    const broadcastId = Number(
      envelope.payload.broadcastId
      || (envelope.payload.broadcast as Record<string, unknown> | undefined)?.id,
    )
    if (!Number.isSafeInteger(broadcastId) || broadcastId <= 0) return

    if (envelope.event === 'BROADCAST_UPDATED') {
      const status = String(envelope.payload.status || '')
      if (status === 'DELETED') {
        if (emergencyAlert.value?.broadcast.id === broadcastId) emergencyAlert.value = null
        if (selected.value?.broadcast.id === broadcastId) clearSelection()
        return
      }
      if (status === 'CANCELLED'
        && emergencyAlert.value?.broadcast.id === broadcastId) {
        emergencyAlert.value = null
      }
      if (selected.value?.broadcast.id === broadcastId) {
        const detail = await api.broadcasts.detail(broadcastId).catch(() => null)
        if (detail) selected.value = detail
        await refreshStatistics().catch(() => undefined)
      }
      return
    }

    try {
      const detail = await api.broadcasts.detail(broadcastId)
      if (detail.broadcast.priority === 'EMERGENCY' && detail.receiver) {
        emergencyAlert.value = detail
      }
    } catch {
      // 实时事件与权限变更可能并发；REST 可见列表始终是最终依据。
    }
  }

  return {
    broadcasts: readonly(broadcasts),
    pending: readonly(pending),
    pendingIds,
    pendingCount,
    selectedId: readonly(selectedId),
    selected: readonly(selected),
    statistics: readonly(statistics),
    emergencyAlert: readonly(emergencyAlert),
    listLoading: readonly(listLoading),
    detailLoading: readonly(detailLoading),
    loading,
    saving: readonly(saving),
    cancelling: readonly(cancelling),
    deleting: readonly(deleting),
    load,
    selectBroadcast,
    createBroadcast,
    cancelBroadcast,
    deleteBroadcast,
    confirm,
    complete,
    refreshStatistics,
    clearSelection,
    closeEmergencyAlert,
  }
}
