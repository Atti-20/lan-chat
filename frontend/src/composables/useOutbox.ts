import { computed, readonly, ref, shallowRef } from 'vue'
import {
  deleteOutboxEntry,
  loadOutbox,
  saveOutboxEntry,
} from '../services/localChatDb'
import type { OutboxEntry } from '../types'

export function useOutbox() {
  const entries = ref<OutboxEntry[]>([])
  const hydrated = shallowRef(false)
  const durable = shallowRef(true)

  const pendingCount = computed(() => entries.value.filter((entry) => entry.state !== 'FAILED').length)
  const failedCount = computed(() => entries.value.filter((entry) => entry.state === 'FAILED').length)

  async function hydrate(): Promise<void> {
    let stored: OutboxEntry[]
    try {
      stored = await loadOutbox()
    } catch (cause) {
      durable.value = false
      throw cause
    }
    entries.value = stored.map((entry) => entry.state === 'SENDING'
      ? { ...entry, state: 'WAITING_NETWORK' }
      : entry)
    await Promise.all(entries.value.map((entry) => persist(saveOutboxEntry(entry))))
    hydrated.value = true
  }

  async function enqueue(entry: OutboxEntry): Promise<void> {
    const index = entries.value.findIndex((item) => item.clientMsgId === entry.clientMsgId)
    entries.value = index < 0
      ? [...entries.value, entry]
      : entries.value.map((item, itemIndex) => itemIndex === index ? entry : item)
    await persist(saveOutboxEntry(entry))
  }

  async function update(
    clientMsgId: string,
    patch: Partial<Pick<OutboxEntry, 'state' | 'retryCount' | 'lastError'>>,
  ): Promise<void> {
    const current = entries.value.find((entry) => entry.clientMsgId === clientMsgId)
    if (!current) return
    const next = { ...current, ...patch }
    entries.value = entries.value.map((entry) => entry.clientMsgId === clientMsgId ? next : entry)
    await persist(saveOutboxEntry(next))
  }

  async function remove(clientMsgId: string): Promise<void> {
    entries.value = entries.value.filter((entry) => entry.clientMsgId !== clientMsgId)
    await persist(deleteOutboxEntry(clientMsgId))
  }

  async function retryFailed(): Promise<void> {
    const failed = entries.value.filter((entry) => entry.state === 'FAILED')
    await Promise.all(failed.map((entry) => update(entry.clientMsgId, {
      state: 'WAITING_NETWORK',
      lastError: undefined,
    })))
  }

  function readyEntries(): OutboxEntry[] {
    return entries.value
      .filter((entry) => entry.state === 'WAITING_NETWORK')
      .sort((first, second) => first.createdAt.localeCompare(second.createdAt))
  }

  async function persist(operation: Promise<void>): Promise<void> {
    try {
      await operation
    } catch {
      // 保留内存队列，让当前页面仍可恢复发送；UI 会提示关闭页面前不要退出。
      durable.value = false
    }
  }

  return {
    entries: readonly(entries),
    hydrated: readonly(hydrated),
    durable: readonly(durable),
    pendingCount,
    failedCount,
    hydrate,
    enqueue,
    update,
    remove,
    retryFailed,
    readyEntries,
  }
}
