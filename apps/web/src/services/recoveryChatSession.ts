import { upsertOutbox } from '../../../../packages/domain-ts/src/outbox'
import type { OutboxEntry } from '../../../../packages/domain-ts/src/models'
import { quarantineRecovery, storageBlockedRecovery, RecoveryProtocolError, type RecoveryState } from '../../../../packages/domain-ts/src/recovery'
import { RecoveryCoordinator, type RecoveryOwner, type RecoveryTransport } from './recoveryCoordinator'
import { RecoveryChatSink, type RecoveredChatImage, type RecoveryChatStorage } from './recoveryChatSink'

/** Owns one authenticated page's recovery and serialized format2 writes. */
export class RecoveryChatSession {
  state: RecoveryState | null = null
  image: RecoveredChatImage | null = null
  reason: string | undefined
  private coordinator?: RecoveryCoordinator
  private sink?: RecoveryChatSink
  private liveCandidates=new Set<string>()
  noteLiveCandidate(id:string):void {if(this.safe && id.length>0 && id.length<=128 && !this.state?.messages.has(id) && this.liveCandidates.size<1000) this.liveCandidates.add(id)}
  private turn=0
  private writes: Promise<void> = Promise.resolve()
  constructor(private readonly transport: RecoveryTransport, readonly owner: RecoveryOwner,
    private readonly current: () => boolean,
    private readonly seed: () => readonly OutboxEntry[],
    private readonly changed: () => void,
    private readonly storage?: RecoveryChatStorage) {}
  get safe(): boolean {return this.current() && this.state?.phase==='ONLINE_SAFE'}
  canSend(cid: string): boolean {return this.safe && this.state?.access.get(cid)?.sendAllowed===true}
  quarantine(reason='DISCONNECTED'): void {
    this.turn++;this.coordinator?.cancel();this.reason=reason
    if(this.state) this.state=quarantineRecovery(this.state,reason)
    this.changed()
  }
  async rebuild(key: string, preferResume = false): Promise<void> {
    const previous=preferResume && this.safe ? this.state ?? undefined : undefined
    this.quarantine('RECOVERING')
    const turn=this.turn
    const valid=()=>turn===this.turn && this.current()
    await this.writes.catch(()=>undefined)
    if(!valid()) return
    const sink=previous && this.sink ? this.sink : new RecoveryChatSink(()=>({outbox:this.image?.outbox ?? this.seed()}),()=>this.current(),
      (state,image)=>{if(this.current()){this.state=state;this.image=image;this.liveCandidates.clear();this.reason=undefined;this.changed()}},
      reason=>{if(this.current()){this.reason=reason;if(this.state)this.state=quarantineRecovery(this.state,reason);this.changed()}},this.storage,()=>[...this.liveCandidates])
    this.sink=sink
    const coordinator=new RecoveryCoordinator(this.transport,sink,()=>valid());this.coordinator=coordinator
    const state=await coordinator.rebuild(this.owner,key,previous)
    if(!valid()) return
    this.state=state;this.reason=coordinator.reason;this.changed()
    if(!this.safe) throw new RecoveryProtocolError(this.reason || 'RECOVERY_REQUIRED')
  }
  acknowledgeNotification(key:string):Promise<void> {return this.mutateOutbox(entries=>[...entries],key)}
  writeOutbox(entries: readonly OutboxEntry[]): Promise<void> {
    const captured=JSON.parse(JSON.stringify(entries)) as OutboxEntry[]
    return this.mutateOutbox(()=>captured)
  }
  saveOutbox(entry: OutboxEntry): Promise<void> {
    const captured=JSON.parse(JSON.stringify(entry)) as OutboxEntry
    return this.mutateOutbox(entries=>upsertOutbox(entries,captured))
  }
  deleteOutbox(id: string): Promise<void> {return this.mutateOutbox(entries=>entries.filter(e=>e.clientMsgId!==id))}
  private mutateOutbox(change: (entries: readonly OutboxEntry[]) => OutboxEntry[],effectKey?:string): Promise<void> {
    const turn=this.turn
    const operation=this.writes.then(async()=>{
      if(turn!==this.turn || !this.safe || !this.sink || !this.state) throw new RecoveryProtocolError('RECOVERY_REQUIRED')
      const state=this.state,sink=this.sink
      if(effectKey) sink.acknowledgeNotification(state.context,effectKey)
      else sink.replaceOutbox(state.context,change(this.image?.outbox ?? []))
      try {await sink.commit(state.context,state)} catch(error) {
        if(turn===this.turn && this.current()) {this.state=storageBlockedRecovery(state);this.reason='PERSISTENCE_FAILED';this.changed()}
        throw error
      }
      if(turn!==this.turn || !this.current()) throw new RecoveryProtocolError('STALE_RECOVERY')
      sink.publish(state.context,state)
    })
    this.writes=operation.catch(()=>undefined)
    return operation
  }
}
