import { computed, readonly, ref, shallowRef } from 'vue'
import { patchOutbox, readyOutboxEntries, recoverOutbox, upsertOutbox, type OutboxPatch } from '../../../../packages/domain-ts/src/outbox'
import type { OutboxPort } from '../../../../packages/platform-ports/src/outbox'
import { browserOutboxStore } from '../platform/web/outboxStore'
import type { OutboxEntry } from '../types'

export function useOutbox(store: OutboxPort = browserOutboxStore, options: {strictPersistence?: () => boolean} = {}) {
  const entries = ref<OutboxEntry[]>([])
  const hydrated = shallowRef(false)
  const durable = shallowRef(true)

  const pendingCount = computed(() => entries.value.filter((entry) => entry.state !== 'FAILED').length)
  const failedCount = computed(() => entries.value.filter((entry) => entry.state === 'FAILED').length)

  async function hydrate(): Promise<void> {
    let stored: OutboxEntry[]
    try {
      stored = await store.load()
    } catch (cause) {
      durable.value = false
      throw cause
    }
    const recovery = recoverOutbox(stored)
    entries.value = recovery.entries
    for (const entry of recovery.recovered) {
      await persist(() => store.save(entry))
    }
    hydrated.value = true
  }

  async function enqueue(entry: OutboxEntry): Promise<void> {
    entries.value = upsertOutbox(entries.value, entry)
    await persist(() => store.save(entry))
  }

  async function update(
    clientMsgId: string,
    patch: OutboxPatch,
  ): Promise<void> {
    const update = patchOutbox(entries.value, clientMsgId, patch)
    if (!update) return
    entries.value = update.entries
    await persist(() => store.save(update.changed))
  }

  async function remove(clientMsgId: string): Promise<void> {
    entries.value = entries.value.filter((entry) => entry.clientMsgId !== clientMsgId)
    await persist(() => store.delete(clientMsgId))
  }

  async function retryFailed(): Promise<void> {
    const failed = entries.value.filter((entry) => entry.state === 'FAILED')
    for (const entry of failed) {
      await update(entry.clientMsgId, {
        state: 'WAITING_NETWORK',
        lastError: undefined,
      })
    }
  }

  function readyEntries(): OutboxEntry[] {
    return readyOutboxEntries(entries.value)
  }

  async function persist(operation: () => Promise<void>): Promise<void> {
    try {
      await operation()
      durable.value = true
    } catch (cause) {
      if (options.strictPersistence?.()) { durable.value = false; throw cause }
      // 保留内存队列，让当前页面仍可恢复发送；UI 会提示关闭页面前不要退出。
      durable.value = false
    }
  }

  return {
    adoptRecovered: (recovered: readonly OutboxEntry[]) => { entries.value = [...recovered]; hydrated.value = true; durable.value = true },
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
