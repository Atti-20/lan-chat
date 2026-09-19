import type { OutboxEntry } from '../../domain-ts/src/models'

/** Persistence only. Vue state, retry policy, ACK waiting and ownership stay above this port. */
export interface OutboxPort {
  load(): Promise<OutboxEntry[]>
  save(entry: OutboxEntry): Promise<void>
  delete(clientMsgId: string): Promise<void>
}
