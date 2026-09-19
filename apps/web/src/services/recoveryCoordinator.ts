import { acceptRecoveryReady, planRecoveryPage, quarantineRecovery, recoveryDecimal, RecoveryProtocolError,
  sameRecoveryContext, storageBlockedRecovery, type RecoveryContext, type RecoveryPlan, type RecoveryState } from '../../../../packages/domain-ts/src/recovery'
import { beginFullRecoverySnapshot, stageRecoverySnapshotPage, type SnapshotItem } from '../../../../packages/domain-ts/src/recoverySnapshot'

export type RecoveryOwner = Omit<RecoveryContext, 'streamEpoch'>
export interface RecoveryTransport {
  capabilities(): Promise<unknown>
  open(input: {protocolVersion: number; mode: string; cursor?: {streamEpoch: string; position: string}}, key: string): Promise<unknown>
  snapshot(id: string, token?: string, limit?: number): Promise<unknown>
  cut(id: string): Promise<unknown>
  mutations(id: string, after: string, through: string, limit?: number): Promise<unknown>
  ready(id: string, cursor: string, complete: boolean): Promise<unknown>
  release(id: string): Promise<void>
}
/** Every sink operation is scoped; implementations must not mutate another active context. */
export interface RecoverySink {
  begin(context: RecoveryContext): Promise<void>
  resume(context: RecoveryContext, state: RecoveryState): Promise<void>
  snapshot(context: RecoveryContext, items: readonly SnapshotItem[]): Promise<void>
  mutations(context: RecoveryContext, plan: RecoveryPlan): Promise<void>
  commit(context: RecoveryContext, state: RecoveryState): Promise<void>
  publish(context: RecoveryContext, state: RecoveryState): void
  quarantine(reason: string): void
}
function record(value: unknown): Record<string,unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new RecoveryProtocolError('PROTOCOL_ERROR')
  return value as Record<string,unknown>
}
function identifier(value: unknown): string {
  if (typeof value !== 'string' || !value || value.length > 128) throw new RecoveryProtocolError('PROTOCOL_ERROR')
  return value
}

/** Full rebuild coordinator. No caller may publish staging data before successful durable READY. */
export class RecoveryCoordinator {
  private turn = 0
  state: RecoveryState | null = null
  durableCursor: string | null = null
  reason: string | undefined
  constructor(private readonly transport: RecoveryTransport, private readonly sink: RecoverySink,
    private readonly isCurrent: (owner: RecoveryOwner) => boolean) {}

  cancel(): void {
    this.turn++
    if (this.state) this.state = quarantineRecovery(this.state,'DISCONNECTED')
    this.sink.quarantine('DISCONNECTED')
  }

  async rebuild(owner: RecoveryOwner, idempotencyKey: string, resumeFrom?: RecoveryState): Promise<RecoveryState | null> {
    owner={...owner}
    const turn=++this.turn
    let outcome: RecoveryState | null = null
    let context: RecoveryContext | undefined, sessionId: string | undefined
    const valid=()=>turn===this.turn && this.isCurrent(owner)
    const check=()=>{if(!valid()) throw new RecoveryProtocolError('STALE_RECOVERY')}
    if (!valid()) return null
    this.state=null;this.durableCursor=null;this.reason=undefined;this.sink.quarantine('RECOVERING')
    try {
      const capability=record(await this.transport.capabilities());check()
      if (capability.capability !== 'meshx.mutation-recovery' || capability.recordVersion !== 1
        || !Array.isArray(capability.versions) || !capability.versions.includes(1)) throw new RecoveryProtocolError('BLOCKED_UPGRADE')
      const limit=capability.maxPageSize
      if (!Number.isInteger(limit) || Number(limit)<1 || Number(limit)>200) throw new RecoveryProtocolError('PROTOCOL_ERROR')
      if(resumeFrom && (resumeFrom.phase!=='ONLINE_SAFE' || resumeFrom.context.origin!==owner.origin || resumeFrom.context.userId!==owner.userId || resumeFrom.context.generation!==owner.generation)) throw new RecoveryProtocolError('STALE_RECOVERY')
      const opened=record(await this.transport.open(resumeFrom
        ? {protocolVersion:1,mode:'resume',cursor:{streamEpoch:resumeFrom.context.streamEpoch,position:resumeFrom.cursor}}
        : {protocolVersion:1,mode:'rebuild'},idempotencyKey))
      // Capture the owned session so cancellation after creation can still release it.
      sessionId=identifier(opened.recoveryId);check()
      const boundary=identifier(opened.startCursor)
      if (recoveryDecimal(opened.floor)>recoveryDecimal(boundary) || recoveryDecimal(opened.latest)<recoveryDecimal(boundary)) throw new RecoveryProtocolError('PROTOCOL_ERROR')
      context={...owner,streamEpoch:identifier(opened.streamEpoch)}
      let state: RecoveryState
      if(opened.mode==='resume') {
        if(!resumeFrom || !sameRecoveryContext(resumeFrom.context,context) || boundary!==resumeFrom.cursor) throw new RecoveryProtocolError('STREAM_RESET')
        state={...resumeFrom,phase:'CATCHING_UP',reason:undefined}
        await this.sink.resume(context,state);check();this.state=state
      } else {
        if(opened.mode!=='rebuild' || opened.snapshotBoundary!==boundary) throw new RecoveryProtocolError('PROTOCOL_ERROR')
        let stage=beginFullRecoverySnapshot(context,identifier(opened.snapshotId),boundary)
        this.state=stage.state
        await this.sink.begin(context);check()
        do {
          const response=await this.transport.snapshot(sessionId,stage.nextToken ?? undefined,Number(limit));check()
          const page=stageRecoverySnapshotPage(stage,context,stage.nextToken,response,Number(limit))
          await this.sink.snapshot(context,page.items);check()
          stage=page.stage;this.state=stage.state
        } while (!stage.complete)
        state=stage.state
      }
      for (let round=0;round<64;round++) {
        const cut=record(await this.transport.cut(sessionId));check()
        if (cut.streamEpoch !== context.streamEpoch) throw new RecoveryProtocolError('STREAM_RESET')
        const through=identifier(cut.through)
        if (recoveryDecimal(cut.floor)>recoveryDecimal(state.cursor)) throw new RecoveryProtocolError('CURSOR_EXPIRED')
        if (recoveryDecimal(through)<recoveryDecimal(state.cursor)) throw new RecoveryProtocolError('CURSOR_AHEAD')
        while (recoveryDecimal(state.cursor)<recoveryDecimal(through)) {
          const response=await this.transport.mutations(sessionId,state.cursor,through,Number(limit));check()
          const plan=planRecoveryPage(state,context,state.cursor,through,response,Number(limit))
          if (!plan.changed || plan.next.phase==='QUARANTINED') throw new RecoveryProtocolError(plan.next.reason || 'PROTOCOL_ERROR')
          await this.sink.mutations(context,plan);check()
          state=plan.next;this.state=state
        }
        if (state.rebuild.size) throw new RecoveryProtocolError('REBUILD_REQUIRED')
        try { await this.sink.commit(context,state) }
        catch (error) { if (valid()) this.state=storageBlockedRecovery(state); throw error }
        check()
        this.durableCursor=state.cursor
        const reply=await this.transport.ready(sessionId,state.cursor,true);check()
        state=acceptRecoveryReady(state,context,through,true,reply);this.state=state
        if (state.phase==='QUARANTINED') throw new RecoveryProtocolError(state.reason || 'PROTOCOL_ERROR')
        if (state.phase==='ONLINE_SAFE') {this.sink.publish(context,state);outcome=state;break}
      }
      if (!outcome) throw new RecoveryProtocolError('RECOVERY_BUSY')
    } catch (error) {
      if (valid()) {
        const data=error && typeof error==='object' ? (error as {data?:unknown}).data : undefined
        const remote=data && typeof data==='object' ? (data as {reason?:unknown}).reason : undefined
        this.reason=this.state?.phase==='STORAGE_BLOCKED'?'PERSISTENCE_FAILED':error instanceof RecoveryProtocolError ? error.reason
          : typeof remote==='string' && /^[A-Z_]{1,64}$/.test(remote) ? remote : 'RECOVERY_UNAVAILABLE'
        if (this.state && this.state.phase!=='STORAGE_BLOCKED') this.state=quarantineRecovery(this.state,this.reason)
        this.sink.quarantine(this.reason);outcome=this.state
      }
    } finally {
      // Do not use a new account's transport to release an old account's session.
      if (sessionId && this.isCurrent(owner)) {try {await this.transport.release(sessionId)} catch { /* Server TTL releases the pin. */ }}
    }
    return valid()?outcome:null
  }
}
