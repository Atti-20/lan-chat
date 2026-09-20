import type { OutboxEntry } from './models'

export type OutboxPatch = Partial<Pick<OutboxEntry, 'state' | 'retryCount' | 'lastError'>>

function enforceRecoveryHold(entry: OutboxEntry): OutboxEntry {
  if (!entry.recoveryDisposition) return entry
  return { ...entry, state: 'FAILED', lastError: entry.recoveryDisposition,
    payload: entry.recoveryDisposition === 'DROP_BODY_REVOKED'
      ? { contentType: 'text', content: '', isBurn: false } : entry.payload }
}

/** A restarted client retries the same idempotency key; it does not mint a new ID. */
export function recoverOutbox(stored: readonly OutboxEntry[]): {
  entries: OutboxEntry[]
  recovered: OutboxEntry[]
} {
  const entries = stored.map((entry): OutboxEntry => entry.recoveryDisposition ? enforceRecoveryHold(entry) : entry.state === 'SENDING'
    ? { ...entry, state: 'WAITING_NETWORK' }
    : entry)
  return {
    entries,
    recovered: entries.filter((entry, index) => entry !== stored[index]),
  }
}

export function upsertOutbox(entries: readonly OutboxEntry[], entry: OutboxEntry): OutboxEntry[] {
  const index = entries.findIndex((item) => item.clientMsgId === entry.clientMsgId)
  const previous = entries[index]
  const next = previous?.recoveryDisposition ? enforceRecoveryHold({ ...previous,
    recoveryDisposition: entry.recoveryDisposition === 'DROP_BODY_REVOKED' ? 'DROP_BODY_REVOKED' : previous.recoveryDisposition,
  }) : enforceRecoveryHold(entry)
  return index < 0
    ? [...entries, next]
    : entries.map((item, itemIndex) => itemIndex === index ? next : item)
}

export function patchOutbox(
  entries: readonly OutboxEntry[], clientMsgId: string, patch: OutboxPatch,
): { entries: OutboxEntry[]; changed: OutboxEntry } | null {
  const current = entries.find((entry) => entry.clientMsgId === clientMsgId)
  if (!current) return null
  const changed = enforceRecoveryHold({ ...current, ...patch })
  return { entries: entries.map((entry) => entry.clientMsgId === clientMsgId ? changed : entry), changed }
}

export function readyOutboxEntries(entries: readonly OutboxEntry[]): OutboxEntry[] {
  return entries
    .filter((entry) => entry.state === 'WAITING_NETWORK' && !entry.recoveryDisposition)
    .sort((first, second) => first.createdAt.localeCompare(second.createdAt))
}
