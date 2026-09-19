/** Body-free recovery proofs. Adapters commit these and the existing chat/outbox data atomically. */
export type RecoveryPhase = 'QUARANTINED' | 'CATCHING_UP' | 'ONLINE_SAFE' | 'STORAGE_BLOCKED' | 'BLOCKED_UPGRADE'
export type MessageRecoveryState = 'NORMAL' | 'RECALLED' | 'BURNED' | 'UNAVAILABLE'
export interface RecoveryContext { readonly origin: string; readonly userId: string; readonly streamEpoch: string; readonly generation: number }
export interface MessageProof { readonly conversationId: string; readonly objectVersion: string; readonly state: MessageRecoveryState }
export interface AccessProof { readonly accessVersion: string; readonly readAllowed: boolean; readonly sendAllowed: boolean }
export interface RecoveryState {
  readonly context: RecoveryContext
  readonly phase: RecoveryPhase
  readonly cursor: string
  readonly messages: ReadonlyMap<string, MessageProof>
  readonly access: ReadonlyMap<string, AccessProof>
  readonly seen: ReadonlyMap<string, string>
  readonly rebuild: ReadonlySet<string>
  readonly reason?: string
}
interface RecordBase { recordVersion: 1; eventId: string; streamEpoch: string; cursor: string; conversationId: string; committedAt: string }
export type RecoveryMutation = RecordBase & (
  { type: 'MESSAGE_RECALLED' | 'MESSAGE_BURNED' | 'MESSAGE_UNAVAILABLE'; messageId: string; objectVersion: string }
  | { type: 'CONVERSATION_ACCESS_REVOKED'; accessVersion: string; readAllowed: false; sendAllowed: false; reason: string }
  | { type: 'CONVERSATION_ACCESS_CHANGED'; accessVersion: string; readAllowed: true; sendAllowed: boolean; rebuildConversation: boolean; reason: string }
)
export interface RecoveryEffects {
  readonly eraseMessages: ReadonlySet<string>
  readonly revokeConversations: ReadonlySet<string>
  readonly stopAutomaticSend: ReadonlySet<string>
  readonly rebuildConversations: ReadonlySet<string>
}
export interface RecoveryPlan { readonly next: RecoveryState; readonly effects: RecoveryEffects; readonly changed: boolean; readonly ignored: boolean }
export class RecoveryProtocolError extends Error {
  constructor(readonly reason: string) { super(reason) }
}
const fail = (reason = 'PROTOCOL_ERROR'): never => { throw new RecoveryProtocolError(reason) }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function recoveryDecimal(value: unknown, allowZero = true): bigint {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(value)) return fail()
  const parsed = BigInt(value)
  if (parsed > 9223372036854775807n || (!allowZero && parsed === 0n)) return fail()
  return parsed
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 128) return fail()
  return value
}
function exactKeys(value: Record<string, unknown>, keys: readonly string[]): void {
  if (Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) fail()
}
export function parseRecoveryMutation(raw: unknown): RecoveryMutation {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fail()
  const value = raw as Record<string, unknown>
  if (value.recordVersion !== 1) return fail('UNSUPPORTED_SECURE_STATE')
  const base: RecordBase = { recordVersion: 1, eventId: text(value.eventId), streamEpoch: text(value.streamEpoch),
    cursor: text(value.cursor), conversationId: text(value.conversationId), committedAt: text(value.committedAt) }
  if (!uuid.test(base.eventId) || !uuid.test(base.streamEpoch)) return fail()
  recoveryDecimal(base.cursor, false)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(base.committedAt)
      || !Number.isFinite(Date.parse(base.committedAt)) || new Date(base.committedAt).toISOString() !== base.committedAt) return fail()
  const common = ['recordVersion', 'eventId', 'streamEpoch', 'cursor', 'conversationId', 'committedAt', 'type']
  const type = value.type
  if (type === 'MESSAGE_RECALLED' || type === 'MESSAGE_BURNED' || type === 'MESSAGE_UNAVAILABLE') {
    exactKeys(value, [...common, 'messageId', 'objectVersion'])
    const objectVersion = text(value.objectVersion); recoveryDecimal(objectVersion, false)
    return { ...base, type, messageId: text(value.messageId), objectVersion }
  }
  if (type !== 'CONVERSATION_ACCESS_REVOKED' && type !== 'CONVERSATION_ACCESS_CHANGED') return fail('UNSUPPORTED_SECURE_STATE')
  const accessVersion = text(value.accessVersion); recoveryDecimal(accessVersion, false)
  const reason = text(value.reason)
  if (type === 'CONVERSATION_ACCESS_REVOKED') {
    exactKeys(value, [...common, 'accessVersion', 'readAllowed', 'sendAllowed', 'reason'])
    if (value.readAllowed !== false || value.sendAllowed !== false || !['REMOVED', 'GROUP_REMOVED', 'DESTROYED', 'ACCESS_REVOKED'].includes(reason)) return fail()
    return { ...base, type, accessVersion, readAllowed: false, sendAllowed: false, reason }
  }
  exactKeys(value, [...common, 'accessVersion', 'readAllowed', 'sendAllowed', 'rebuildConversation', 'reason'])
  if (value.readAllowed !== true || typeof value.sendAllowed !== 'boolean' || typeof value.rebuildConversation !== 'boolean'
      || !['FRIEND_DELETED', 'SEND_DENIED', 'GRANTED', 'UPDATED'].includes(reason)
      || (['FRIEND_DELETED', 'SEND_DENIED'].includes(reason) && (value.sendAllowed || value.rebuildConversation))
      || (reason === 'GRANTED' && !value.rebuildConversation)) return fail()
  return { ...base, type, accessVersion, readAllowed: true, sendAllowed: value.sendAllowed, rebuildConversation: value.rebuildConversation, reason }
}
export function sameRecoveryContext(first: RecoveryContext, second: RecoveryContext): boolean {
  return first.origin === second.origin && first.userId === second.userId && first.streamEpoch === second.streamEpoch && first.generation === second.generation
}
export function initialRecoveryState(context: RecoveryContext): RecoveryState {
  if (!context.origin || !uuid.test(context.streamEpoch) || !Number.isSafeInteger(context.generation) || context.generation < 0) return fail()
  recoveryDecimal(context.userId, false)
  return { context: { ...context }, phase: 'QUARANTINED', cursor: '0', messages: new Map(), access: new Map(), seen: new Map(), rebuild: new Set() }
}
function effects(): { eraseMessages: Set<string>; revokeConversations: Set<string>; stopAutomaticSend: Set<string>; rebuildConversations: Set<string> } {
  return { eraseMessages: new Set(), revokeConversations: new Set(), stopAutomaticSend: new Set(), rebuildConversations: new Set() }
}
function fingerprint(record: RecoveryMutation): string {
  return JSON.stringify([record.eventId, record.streamEpoch, record.cursor, record.type, record.conversationId,
    'messageId' in record ? record.messageId : null, 'objectVersion' in record ? record.objectVersion : null,
    'accessVersion' in record ? record.accessVersion : null, 'readAllowed' in record ? record.readAllowed : null,
    'sendAllowed' in record ? record.sendAllowed : null, 'rebuildConversation' in record ? record.rebuildConversation : null,
    'reason' in record ? record.reason : null, record.committedAt])
}

/** One plan per page: failure discards every staged change, including its cursor and cleanup effects. */
export function planRecoveryMutations(current: RecoveryState, source: RecoveryContext, raw: readonly unknown[]): RecoveryPlan {
  if (!sameRecoveryContext(current.context, source)) return { next: current, effects: effects(), changed: false, ignored: true }
  if (current.phase === 'STORAGE_BLOCKED' || current.phase === 'BLOCKED_UPGRADE') return { next: current, effects: effects(), changed: false, ignored: true }
  const messages = new Map(current.messages), access = new Map(current.access), seen = new Map(current.seen), rebuild = new Set(current.rebuild)
  const nextEffects = effects()
  let cursor = current.cursor, changed = false
  try {
    if (raw.length > 200) fail()
    for (const value of raw) {
      const record = parseRecoveryMutation(value)
      if (record.streamEpoch !== current.context.streamEpoch) fail('STREAM_RESET')
      const position = recoveryDecimal(record.cursor, false), last = recoveryDecimal(cursor), identity = fingerprint(record)
      const eventPosition = seen.get(`event:${record.eventId}`)
      if (eventPosition !== undefined && eventPosition !== record.cursor) fail()
      if (position <= last) {
        if (seen.has(record.cursor) && seen.get(record.cursor) !== identity) fail()
        continue
      }
      if (position !== last + 1n) fail('CURSOR_GAP')
      if ('messageId' in record) {
        const previous = messages.get(record.messageId), state = record.type.slice('MESSAGE_'.length) as Exclude<MessageRecoveryState, 'NORMAL'>
        if (previous && previous.conversationId !== record.conversationId) fail()
        const version = recoveryDecimal(record.objectVersion, false), before = previous ? recoveryDecimal(previous.objectVersion, false) : 0n
        if (version === before && previous?.state !== state) fail()
        if (version > before) {
          if (previous && previous.state !== 'NORMAL' && previous.state !== state && state !== 'UNAVAILABLE') fail()
          messages.set(record.messageId, { conversationId: record.conversationId, objectVersion: record.objectVersion, state })
          nextEffects.eraseMessages.add(record.messageId)
        }
      } else {
        const previous = access.get(record.conversationId), version = recoveryDecimal(record.accessVersion, false)
        const before = previous ? recoveryDecimal(previous.accessVersion, false) : 0n
        if (version === before && (previous?.readAllowed !== record.readAllowed || previous?.sendAllowed !== record.sendAllowed)) fail()
        if (version >= before) {
          access.set(record.conversationId, { accessVersion: record.accessVersion, readAllowed: record.readAllowed, sendAllowed: record.sendAllowed })
          if (!record.readAllowed) {
            nextEffects.revokeConversations.add(record.conversationId); nextEffects.stopAutomaticSend.add(record.conversationId)
            rebuild.delete(record.conversationId)
          } else {
            if (!record.sendAllowed) nextEffects.stopAutomaticSend.add(record.conversationId)
            if (record.rebuildConversation) { rebuild.add(record.conversationId); nextEffects.rebuildConversations.add(record.conversationId) }
          }
        }
      }
      seen.set(record.cursor, identity); seen.set(`event:${record.eventId}`, record.cursor); cursor = record.cursor; changed = true
    }
    if (!changed) return { next: current, effects: nextEffects, changed: false, ignored: false }
    return { next: { ...current, cursor, messages, access, seen, rebuild, reason: undefined,
      phase: current.phase === 'QUARANTINED' || rebuild.size ? 'CATCHING_UP' : current.phase }, effects: nextEffects, changed, ignored: false }
  } catch (error) {
    if (!(error instanceof RecoveryProtocolError)) throw error
    return { next: { ...current, phase: 'QUARANTINED', reason: error.reason }, effects: effects(), changed: false, ignored: false }
  }
}

export function recoveryMessageVisible(state: RecoveryState, messageId: string): boolean {
  const proof = state.messages.get(messageId)
  return state.phase === 'ONLINE_SAFE' && proof?.state === 'NORMAL' && state.access.get(proof.conversationId)?.readAllowed === true && !state.rebuild.has(proof.conversationId)
}

/** Validate the envelope against the exact HTTP request before applying any record. */
export function planRecoveryPage(current: RecoveryState, source: RecoveryContext, after: string, cut: string,
  raw: unknown, limit = 100): RecoveryPlan {
  if (!sameRecoveryContext(current.context, source) || current.phase === 'STORAGE_BLOCKED' || current.phase === 'BLOCKED_UPGRADE') {
    return { next: current, effects: effects(), changed: false, ignored: true }
  }
  try {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fail()
    const page = raw as Record<string, unknown>
    exactKeys(page, ['records', 'fromExclusive', 'through', 'nextCursor', 'hasMore', 'floor', 'latest', 'streamEpoch'])
    if (page.streamEpoch !== source.streamEpoch) return fail('STREAM_RESET')
    const start = recoveryDecimal(after), end = recoveryDecimal(cut)
    if (page.fromExclusive !== after || page.through !== cut || start > end || start > recoveryDecimal(current.cursor)
      || !Number.isInteger(limit) || limit < 1 || limit > 200 || !Array.isArray(page.records) || page.records.length > limit) return fail()
    const floor = recoveryDecimal(page.floor), latest = recoveryDecimal(page.latest), next = recoveryDecimal(page.nextCursor)
    if (floor > start) return fail('CURSOR_EXPIRED')
    if (latest < end || next < start || next > end || page.hasMore !== (next < end)
      || next !== start + BigInt(page.records.length) || (next < end && page.records.length !== limit)) return fail()
    for (let index = 0; index < page.records.length; index++) {
      const record = parseRecoveryMutation(page.records[index])
      if (recoveryDecimal(record.cursor) !== start + BigInt(index) + 1n) return fail('CURSOR_GAP')
    }
    return planRecoveryMutations(current, source, page.records)
  } catch (error) {
    if (!(error instanceof RecoveryProtocolError)) throw error
    return { next: quarantineRecovery(current, error.reason), effects: effects(), changed: false, ignored: false }
  }
}
export function quarantineRecovery(state: RecoveryState, reason: string): RecoveryState { return { ...state, phase: 'QUARANTINED', reason } }
export function storageBlockedRecovery(state: RecoveryState): RecoveryState { return { ...state, phase: 'STORAGE_BLOCKED', reason: 'PERSISTENCE_FAILED' } }

/** Call with the last durably committed generation, never an uncommitted page plan. */
export function acceptRecoveryReady(current: RecoveryState, source: RecoveryContext, cut: string,
  snapshotComplete: boolean, raw: unknown): RecoveryState {
  if (!sameRecoveryContext(current.context, source) || (current.phase !== 'CATCHING_UP' && current.phase !== 'ONLINE_SAFE')) return current
  try {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fail()
    const value = raw as Record<string, unknown>
    const allowed = ['ready', 'acceptedCursor', 'latest', 'streamEpoch', 'receiptId']
    if (Object.keys(value).some(key => !allowed.includes(key)) || typeof value.ready !== 'boolean') return fail()
    if (value.streamEpoch !== current.context.streamEpoch) return fail('STREAM_RESET')
    const accepted = recoveryDecimal(value.acceptedCursor), latest = recoveryDecimal(value.latest)
    if (recoveryDecimal(cut) !== accepted || recoveryDecimal(current.cursor) !== accepted || latest < accepted
      || value.ready !== (latest === accepted)) return fail()
    if (value.receiptId !== undefined && value.receiptId !== null) text(value.receiptId)
    if (!snapshotComplete || current.rebuild.size) return { ...current, phase: 'CATCHING_UP' }
    return { ...current, phase: value.ready ? 'ONLINE_SAFE' : 'CATCHING_UP', reason: undefined }
  } catch (error) {
    if (!(error instanceof RecoveryProtocolError)) throw error
    return quarantineRecovery(current, error.reason)
  }
}
