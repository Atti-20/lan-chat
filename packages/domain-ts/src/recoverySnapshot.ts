import { initialRecoveryState, recoveryDecimal, RecoveryProtocolError, sameRecoveryContext,
  type RecoveryContext, type RecoveryState, type MessageRecoveryState } from './recovery'

type Fields = Record<string, unknown>
function fail(): never { throw new RecoveryProtocolError('PROTOCOL_ERROR') }
function object(raw: unknown): Fields { if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fail(); return raw as Fields }
function fields(raw: Fields, required: string[], optional: string[] = []): void {
  if (required.some(key => !Object.hasOwn(raw, key)) || Object.keys(raw).some(key => !required.includes(key) && !optional.includes(key))) fail()
}
function id(raw: unknown): string { if (typeof raw !== 'string' || !raw.trim() || raw.length > 128) return fail(); return raw }
export interface SnapshotItem {
  kind: 'CONVERSATION' | 'MESSAGE'; conversationId: string
  accessVersion?: string; readAllowed?: boolean; sendAllowed?: boolean; messageSequenceAtH?: string
  messageId?: string; objectVersion?: string; state?: MessageRecoveryState; messageSequence?: string
  content?: string; details?: Fields
}
export function parseSnapshotItem(raw: unknown): SnapshotItem {
  const value = object(raw), conversationId = id(value.conversationId)
  if (value.kind === 'CONVERSATION') {
    const read = value.readAllowed, send = value.sendAllowed
    if (typeof read !== 'boolean' || typeof send !== 'boolean' || (!read && send)) return fail()
    fields(value, ['kind','conversationId','accessVersion','readAllowed','sendAllowed', ...(read ? ['messageSequenceAtH'] : [])])
    recoveryDecimal(value.accessVersion, false)
    if (read) recoveryDecimal(value.messageSequenceAtH)
    return {kind:'CONVERSATION',conversationId,accessVersion:value.accessVersion as string,readAllowed:read,sendAllowed:send,
      ...(read ? {messageSequenceAtH:value.messageSequenceAtH as string} : {})}
  }
  if (value.kind !== 'MESSAGE') return fail()
  const state = value.state
  if (typeof state !== 'string' || !['NORMAL','RECALLED','BURNED','UNAVAILABLE'].includes(state)) throw new RecoveryProtocolError('UNSUPPORTED_SECURE_STATE')
  fields(value,['kind','conversationId','messageId','objectVersion','state','messageSequence', ...(state === 'NORMAL' ? ['content','details'] : [])],state === 'NORMAL' ? [] : ['content','details'])
  if (state !== 'NORMAL' && (value.content != null || value.details != null)) return fail()
  const messageId = id(value.messageId)
  recoveryDecimal(value.objectVersion,false); recoveryDecimal(value.messageSequence,false)
  const item: SnapshotItem = {kind:'MESSAGE',conversationId,messageId,objectVersion:value.objectVersion as string,
    state:state as MessageRecoveryState,messageSequence:value.messageSequence as string}
  if (state !== 'NORMAL') return item
  if (typeof value.content !== 'string') return fail()
  const details = object(value.details)
  fields(details,['fromUserId','contentType','createTime','isBurn'],['clientMsgId','burnDuration','replyToId','mentionUserIds'])
  if (!Number.isSafeInteger(details.fromUserId) || Number(details.fromUserId) <= 0 || (details.isBurn !== 0 && details.isBurn !== 1)) return fail()
  id(details.contentType)
  const time = details.createTime
  if (typeof time !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$/.test(time)
    || !Number.isFinite(Date.parse(time+'Z')) || new Date(time+'Z').toISOString().slice(0,19) !== time.slice(0,19)) return fail()
  for (const key of ['clientMsgId','replyToId','mentionUserIds']) if (details[key] !== undefined && typeof details[key] !== 'string') return fail()
  if (details.burnDuration !== undefined && (!Number.isSafeInteger(details.burnDuration) || Number(details.burnDuration) < 0)) return fail()
  return {...item,content:value.content,details:{...details}}
}

/** Transient full-manifest proofs. Body items go directly to the existing application staging models. */
export interface RecoverySnapshotStage {
  state: RecoveryState; snapshotId: string; boundary: string; nextToken: string | null; complete: boolean
  consumedTokens: ReadonlySet<string | null>; positions: ReadonlyMap<string,string>; count: number
  sequences: ReadonlyMap<string,string>
}
export function beginFullRecoverySnapshot(context: RecoveryContext, snapshotId: string, boundary: string): RecoverySnapshotStage {
  id(snapshotId); recoveryDecimal(boundary)
  return {state:{...initialRecoveryState(context),cursor:boundary,phase:'CATCHING_UP'},snapshotId,boundary,nextToken:null,complete:false,
    consumedTokens:new Set(),positions:new Map(),count:0,sequences:new Map()}
}
export function stageRecoverySnapshotPage(current: RecoverySnapshotStage, source: RecoveryContext, requestedToken: string | null,
  raw: unknown, limit = 100): {stage: RecoverySnapshotStage; items: SnapshotItem[]; ignored: boolean} {
  if (!sameRecoveryContext(current.state.context,source) || current.state.phase !== 'CATCHING_UP' || current.consumedTokens.has(requestedToken)) return {stage:current,items:[],ignored:true}
  if (current.complete || requestedToken !== current.nextToken) return fail()
  const page = object(raw)
  fields(page,['snapshotId','boundary','items','nextPageToken','snapshotComplete'])
  if (page.snapshotId !== current.snapshotId || page.boundary !== current.boundary || typeof page.snapshotComplete !== 'boolean'
    || !Array.isArray(page.items) || !Number.isInteger(limit) || limit < 1 || limit > 200 || page.items.length > limit
    || (requestedToken !== null && page.items.length === 0)) return fail()
  const complete = page.snapshotComplete
  const next = complete ? null : id(page.nextPageToken)
  if ((complete && page.nextPageToken !== null) || (!complete && (page.items.length !== limit || next === requestedToken || current.consumedTokens.has(next)))) return fail()
  const messages=new Map(current.state.messages),access=new Map(current.state.access),positions=new Map(current.positions),sequences=new Map(current.sequences)
  const accepted: SnapshotItem[]=[]
  for (const rawItem of page.items) {
    const item=parseSnapshotItem(rawItem),cid=item.conversationId
    if (item.kind === 'CONVERSATION') {
      const old=access.get(cid),version=recoveryDecimal(item.accessVersion!,false),previous=old?recoveryDecimal(old.accessVersion,false):0n
      if (version < previous) continue
      if (version === previous && (old?.readAllowed !== item.readAllowed || old?.sendAllowed !== item.sendAllowed)) return fail()
      access.set(cid,{accessVersion:item.accessVersion!,readAllowed:item.readAllowed!,sendAllowed:item.sendAllowed!})
      if (item.readAllowed) {
        if (positions.has(cid) && positions.get(cid)! !== item.messageSequenceAtH) return fail()
        positions.set(cid,item.messageSequenceAtH!)
      } else positions.delete(cid)
    } else {
      if (!access.has(cid)) return fail()
      if (!access.get(cid)!.readAllowed) continue
      if (recoveryDecimal(item.messageSequence!) > recoveryDecimal(positions.get(cid))) return fail()
      const sequenceKey=JSON.stringify([cid,item.messageSequence]),atSequence=sequences.get(sequenceKey)
      if (atSequence && atSequence !== item.messageId) return fail()
      const identityKey=`id:${item.messageId}`
      if (sequences.has(identityKey) && sequences.get(identityKey) !== sequenceKey) return fail()
      sequences.set(identityKey,sequenceKey)
      sequences.set(sequenceKey,item.messageId!)
      const old=messages.get(item.messageId!),version=recoveryDecimal(item.objectVersion!,false),previous=old?recoveryDecimal(old.objectVersion,false):0n
      if (old && old.conversationId !== cid) return fail()
      if (version < previous) continue
      if (old && ((version === previous && old.state !== item.state) || (version > previous && old.state !== 'NORMAL' && old.state !== item.state && item.state !== 'UNAVAILABLE'))) return fail()
      messages.set(item.messageId!,{conversationId:cid,objectVersion:item.objectVersion!,state:item.state!})
    }
    accepted.push(item)
  }
  const count=current.count+page.items.length
  if (count>200100 || access.size>100 || messages.size>200000) throw new RecoveryProtocolError('RECOVERY_CAPACITY')
  return {stage:{...current,state:{...current.state,messages,access},positions,sequences,count,complete,nextToken:next,
    consumedTokens:new Set([...current.consumedTokens,requestedToken])},items:accepted.filter(item => item.kind === 'CONVERSATION'
      || (access.get(item.conversationId)?.readAllowed && messages.get(item.messageId!)?.state === item.state
        && messages.get(item.messageId!)?.objectVersion === item.objectVersion)),ignored:false}
}
