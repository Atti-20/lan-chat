import type { WsEnvelope } from '../types'

type RealtimeListener = (envelope: WsEnvelope) => void | Promise<void>

const listeners = new Set<RealtimeListener>()

export function subscribeRealtimeEvents(listener: RealtimeListener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export async function publishRealtimeEvent(envelope: WsEnvelope): Promise<void> {
  await Promise.all([...listeners].map((listener) => Promise.resolve(listener(envelope))))
}
