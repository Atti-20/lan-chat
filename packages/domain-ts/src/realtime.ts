import type { ConnectionState } from './models'

/** AUTH_OK is only authenticated; the caller remains SYNCING until the gap is filled. */
export function isRealtimeOnline(state: ConnectionState): boolean {
  return state === 'ONLINE' || state === 'DEGRADED'
}

/** Existing capped exponential backoff. Entropy is supplied by the caller. */
export function reconnectDelay(attempt: number, randomFraction: number): number {
  const exponent = Math.min(attempt - 1, 5)
  const baseDelay = Math.min(1_000 * 2 ** exponent, 30_000)
  const jitter = Math.floor(randomFraction * Math.max(250, baseDelay * 0.25))
  return baseDelay + jitter
}
