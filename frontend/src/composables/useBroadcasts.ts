import { computed, onBeforeUnmount, readonly, ref, shallowRef } from 'vue'
import { api } from '../services/api'
import { subscribeRealtimeEvents } from '../services/realtimeEvents'
import type {
  BroadcastCreatePayload,
  BroadcastDetail,
  BroadcastStatistics,
  EmergencyBroadcast,
  WsEnvelope,
} from '../types'

export function useBroadcasts() {
  const broadcasts = ref<EmergencyBroadcast[]>([])
  const pending = ref<EmergencyBroadcast[]>([])
  const selected = shallowRef<BroadcastDetail | null>(null)
  const statistics = shallowRef<BroadcastStatistics | null>(null)
  const emergencyAlert = shallowRef<BroadcastDetail | null>(null)
  const loading = shallowRef(false)
  const saving = shallowRef(false)

  const pendingIds = computed(() => new Set(pending.value.map((item) => item.id)))
  const pendingCount = computed(() => pending.value.length)
  const unsubscribe = subscribeRealtimeEvents(handleRealtimeEvent)
  onBeforeUnmount(unsubscribe)

  async function load(): Promise<void> {
    loading.value = true
    try {
      const [visible, unresolved] = await Promise.all([
        api.broadcasts.list(),
        api.broadcasts.pending(),
      ])
      broadcasts.value = visible || []
      pending.value = unresolved || []
      const urgent = pending.value.find((item) => item.priority === 'EMERGENCY')
      if (urgent) {
        const detail = await api.broadcasts.detail(urgent.id).catch(() => null)
        if (detail?.receiver && !detail.receiver.confirmedAt) emergencyAlert.value = detail
      }
    } finally {
      loading.value = false
    }
  }

  async function selectBroadcast(broadcastId: number): Promise<void> {
    loading.value = true
    try {
      let detail = await api.broadcasts.detail(broadcastId)
      if (detail.receiver && !detail.receiver.viewedAt) {
        await api.broadcasts.view(broadcastId)
        detail = await api.broadcasts.detail(broadcastId)
      }
      selected.value = detail
      if (emergencyAlert.value?.broadcast.id === broadcastId) emergencyAlert.value = null
      statistics.value = detail.createdByCurrentUser
        ? await api.broadcasts.stats(broadcastId)
        : null
      await refreshListsOnly()
    } finally {
      loading.value = false
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

  async function confirm(status: string, broadcastId = selected.value?.broadcast.id): Promise<void> {
    if (!broadcastId) return
    await api.broadcasts.confirm(broadcastId, status)
    const refreshed = await api.broadcasts.detail(broadcastId)
    if (selected.value?.broadcast.id === broadcastId) selected.value = refreshed
    if (emergencyAlert.value?.broadcast.id === broadcastId) emergencyAlert.value = refreshed
    await refreshListsOnly()
    if (emergencyAlert.value?.receiver?.confirmedAt) emergencyAlert.value = null
  }

  async function refreshStatistics(): Promise<void> {
    const current = selected.value
    const id = current?.broadcast.id
    if (!id || !current.createdByCurrentUser) return
    statistics.value = await api.broadcasts.stats(id)
  }

  function closeEmergencyAlert(): void {
    if (emergencyAlert.value?.broadcast.confirmationRequired
      && !emergencyAlert.value.receiver?.confirmedAt) return
    emergencyAlert.value = null
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
    const broadcastId = Number(envelope.payload.broadcastId
      || (envelope.payload.broadcast as Record<string, unknown> | undefined)?.id)
    if (!Number.isSafeInteger(broadcastId) || broadcastId <= 0) return
    if (envelope.event === 'BROADCAST_UPDATED') {
      if (selected.value?.broadcast.id === broadcastId) await refreshStatistics().catch(() => undefined)
      return
    }
    try {
      const detail = await api.broadcasts.detail(broadcastId)
      if (detail.broadcast.priority === 'EMERGENCY' && detail.receiver) {
        emergencyAlert.value = detail
      }
    } catch {
      // The realtime event may race a permission revocation; the visible list remains authoritative.
    }
  }

  return {
    broadcasts: readonly(broadcasts),
    pending: readonly(pending),
    pendingIds,
    pendingCount,
    selected: readonly(selected),
    statistics: readonly(statistics),
    emergencyAlert: readonly(emergencyAlert),
    loading: readonly(loading),
    saving: readonly(saving),
    load,
    selectBroadcast,
    createBroadcast,
    confirm,
    refreshStatistics,
    closeEmergencyAlert,
  }
}
