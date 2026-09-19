import { RecoveryNotificationJournal, type NotificationJournalImage } from '../../../../packages/domain-ts/src/notificationRecovery'
import type { ChatMessage, OutboxEntry } from '../../../../packages/domain-ts/src/models'
import { upsertOutbox } from '../../../../packages/domain-ts/src/outbox'
import { applyRecoveryData } from '../../../../packages/domain-ts/src/recoveryData'
import { initialRecoveryState, recoveryDecimal, sameRecoveryContext, RecoveryProtocolError,
  type RecoveryContext, type RecoveryPlan, type RecoveryState } from '../../../../packages/domain-ts/src/recovery'
import type { SnapshotItem } from '../../../../packages/domain-ts/src/recoverySnapshot'
import type { RecoverySink } from './recoveryCoordinator'
import { loadRecoverySnapshot, commitRecoverySnapshot } from './localChatDb'

export interface RecoveredChatImage {
  notificationRecovery: NotificationJournalImage
  messages: ChatMessage[]
  outbox: OutboxEntry[]
  directory: SnapshotItem[]
  positions: Record<string, number>
  tombstones: SnapshotItem[]
}
export interface RecoveryChatStorage {
  loadRecoverySnapshot: typeof loadRecoverySnapshot
  commitRecoverySnapshot: typeof commitRecoverySnapshot
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/** Rebuilds existing message/outbox models; directory proof is not a fabricated friend/group profile. */
export class RecoveryChatSink implements RecoverySink {
  private context?: RecoveryContext
  private active = false
  private revision = 0
  private run = 0
  private committedCursor: string | null = null
  private notifications = new RecoveryNotificationJournal()
  private messages = new Map<string, ChatMessage>()
  private outbox: OutboxEntry[] = []
  private directory = new Map<string, SnapshotItem>()
  private positions: Record<string, number> = {}
  private tombstones = new Map<string, SnapshotItem>()
  constructor(private readonly seed: () => {outbox: readonly OutboxEntry[]},
    private readonly isCurrent: (context: RecoveryContext) => boolean,
    private readonly onPublish: (state: RecoveryState, image: RecoveredChatImage) => void,
    private readonly onQuarantine: (reason: string) => void,
    private readonly storage: RecoveryChatStorage = {loadRecoverySnapshot, commitRecoverySnapshot},
    private readonly liveCandidates:()=>readonly string[]=()=>[]) {}
  private check(context: RecoveryContext): void {
    if (!this.active || !this.context || !sameRecoveryContext(this.context,context) || !this.isCurrent(context))
      throw new RecoveryProtocolError('STALE_RECOVERY')
  }
  private owner(context: RecoveryContext): string {return `${context.origin}|${context.userId}`}
  private sequence(raw: unknown): number {
    const value = recoveryDecimal(raw)
    if(value > BigInt(Number.MAX_SAFE_INTEGER)) throw new RecoveryProtocolError('UNSUPPORTED_SECURE_STATE')
    return Number(value)
  }
  async begin(context: RecoveryContext): Promise<void> {
    const run=++this.run
    this.context={...context}; this.active=true; this.committedCursor=null; this.check(context)
    this.messages.clear();this.directory.clear();this.positions={};this.tombstones.clear()
    const seeded=clone(this.seed().outbox)
    const stored=await this.storage.loadRecoverySnapshot(this.owner(context));this.check(context)
    if(run!==this.run) throw new RecoveryProtocolError('STALE_RECOVERY')
    this.notifications=new RecoveryNotificationJournal(stored?.snapshot.notificationRecovery)
    let pending: OutboxEntry[]=[]
    if(stored) {
      if(!Array.isArray(stored.snapshot.outbox)) throw new RecoveryProtocolError('UNSUPPORTED_SECURE_STATE')
      for(const raw of stored.snapshot.outbox) {
        if(!raw || typeof raw!=='object' || typeof raw.clientMsgId!=='string' || !raw.clientMsgId
          || typeof raw.conversationId!=='string' || typeof raw.requestId!=='string'
          || typeof raw.createdAt!=='string' || !Number.isSafeInteger(raw.retryCount) || raw.retryCount<0
          || !['WAITING_NETWORK','SENDING','FAILED'].includes(raw.state)
          || (raw.recoveryDisposition!==undefined && !['NEEDS_USER_ACTION','DROP_BODY_REVOKED'].includes(raw.recoveryDisposition))
          || !raw.payload || typeof raw.payload.content!=='string' || typeof raw.payload.contentType!=='string')
          throw new RecoveryProtocolError('UNSUPPORTED_SECURE_STATE')
        pending=upsertOutbox(pending,clone(raw as OutboxEntry))
      }
    }
    for(const entry of seeded) pending=upsertOutbox(pending,entry)
    this.outbox=applyRecoveryData({next:initialRecoveryState(context),changed:true,ignored:false,
      effects:{eraseMessages:new Set(),revokeConversations:new Set(),rebuildConversations:new Set(),
        stopAutomaticSend:new Set(pending.map(e=>e.conversationId))}},[],pending).outbox
    this.revision=stored?.revision ?? 0
  }
  async resume(context: RecoveryContext, state: RecoveryState): Promise<void> {
    if(!this.context || !sameRecoveryContext(this.context,context) || !sameRecoveryContext(state.context,context) || !this.isCurrent(context))
      throw new RecoveryProtocolError('STALE_RECOVERY')
    this.run++;this.active=true;this.committedCursor=null
  }
  async snapshot(context: RecoveryContext, items: readonly SnapshotItem[]): Promise<void> {
    this.check(context);this.committedCursor=null
    const run=this.run
    const terminal: SnapshotItem[]=[]
    for(const item of items) {
      this.check(context)
      if(run!==this.run) throw new RecoveryProtocolError('STALE_RECOVERY')
      const cid=item.conversationId
      if(item.kind==='CONVERSATION') {
        if(!item.readAllowed) {
          await this.mutations(context,{next:initialRecoveryState(context),changed:true,ignored:false,
            effects:{eraseMessages:new Set(),revokeConversations:new Set([cid]),stopAutomaticSend:new Set([cid]),rebuildConversations:new Set()}})
        } else {this.directory.set(cid,clone(item));this.positions[cid]=this.sequence(item.messageSequenceAtH)}
      } else if(item.state==='NORMAL') {
        const details=item.details!
        const message: ChatMessage={...details,messageId:item.messageId!,conversationId:cid,
          fromUserId:Number(details.fromUserId),type:String(details.contentType),content:item.content,
          sequence:this.sequence(item.messageSequence),deliveryState:'SENT'}
        this.messages.set(message.messageId,message)
      } else {
        terminal.push(item)
      }
    }
    if(terminal.length) {
      const state=initialRecoveryState(context)
      await this.mutations(context,{next:{...state,messages:new Map(terminal.map(item=>[item.messageId!,{
        conversationId:item.conversationId,objectVersion:item.objectVersion!,state:item.state!}]))},changed:true,ignored:false,
        effects:{eraseMessages:new Set(terminal.map(item=>item.messageId!)),revokeConversations:new Set(),stopAutomaticSend:new Set(),rebuildConversations:new Set()}})
      this.check(context)
      if(run!==this.run) throw new RecoveryProtocolError('STALE_RECOVERY')
      for(const item of terminal) this.tombstones.set(item.messageId!,clone(item))
    }
  }

  async mutations(context: RecoveryContext, plan: RecoveryPlan): Promise<void> {
    this.check(context);this.committedCursor=null
    if(!sameRecoveryContext(context,plan.next.context)) throw new RecoveryProtocolError('STALE_RECOVERY')
    const changed=applyRecoveryData(plan,[...this.messages.values()],this.outbox)
    this.messages=new Map(changed.messages.map(message=>[message.messageId,message]));this.outbox=changed.outbox
    for(const cid of plan.effects.revokeConversations) {
      this.directory.delete(cid);delete this.positions[cid]
      for(const [id,item] of this.tombstones) if(item.conversationId===cid) this.tombstones.delete(id)
    }
    for(const id of plan.effects.eraseMessages) {
      const proof=plan.next.messages.get(id)!
      const sequence=this.messages.get(id)?.sequence
      this.tombstones.set(id,{kind:'MESSAGE',conversationId:proof.conversationId,messageId:id,
        objectVersion:proof.objectVersion,state:proof.state,
        ...(sequence ? {messageSequence:String(sequence)} : this.tombstones.get(id)?.messageSequence
          ? {messageSequence:this.tombstones.get(id)!.messageSequence} : {})})
    }
  }
  private image(state: RecoveryState): RecoveredChatImage {
    return clone({notificationRecovery:this.notifications.image(),messages:[...this.messages.values()].filter(m=>state.access.get(m.conversationId || '')?.readAllowed
      && state.messages.get(m.messageId)?.state==='NORMAL'),outbox:this.outbox,
      directory:[...this.directory.values()],positions:this.positions,tombstones:[...this.tombstones.values()]})
  }
  planLiveNotifications(context: RecoveryContext,state: RecoveryState,ids:readonly string[]):void {
    this.check(context);this.committedCursor=null;this.notifications.reconcile(state,ids)
  }
  acknowledgeNotification(context:RecoveryContext,key:string):void {
    this.check(context);this.committedCursor=null;this.notifications.acknowledge(key)
  }
  replaceOutbox(context: RecoveryContext, entries: readonly OutboxEntry[]): void {
    this.check(context);this.committedCursor=null
    this.outbox=clone([...entries])
  }
  async commit(context: RecoveryContext, state: RecoveryState): Promise<void> {
    this.check(context);this.committedCursor=null
    if(!sameRecoveryContext(context,state.context)) throw new RecoveryProtocolError('STALE_RECOVERY')
    const run=this.run
    const absent=new Set(this.outbox.filter(e=>!state.access.get(e.conversationId)?.readAllowed).map(e=>e.conversationId))
    await this.mutations(context,{next:state,changed:absent.size>0,ignored:false,
      effects:{eraseMessages:new Set(),revokeConversations:absent,stopAutomaticSend:absent,rebuildConversations:new Set()}})
    this.check(context)
    if(run!==this.run) throw new RecoveryProtocolError('STALE_RECOVERY')
    this.notifications.reconcile(state,this.liveCandidates().filter(id=>{const message=this.messages.get(id);return message && String(message.fromUserId)!==context.userId}))
    const snapshot={...this.image(state),recovery:{origin:context.origin,userId:context.userId,streamEpoch:context.streamEpoch,
      cursor:state.cursor,messages:Object.fromEntries(state.messages),access:Object.fromEntries(state.access),
      seen:Object.fromEntries(state.seen),rebuild:[...state.rebuild]}}
    const revision=await this.storage.commitRecoverySnapshot(this.owner(context),this.revision,snapshot)
    this.check(context)
    if(run!==this.run) throw new RecoveryProtocolError('STALE_RECOVERY')
    this.revision=revision;this.committedCursor=state.cursor
  }
  publish(context: RecoveryContext, state: RecoveryState): void {
    this.check(context)
    if(!sameRecoveryContext(context,state.context) || state.phase!=='ONLINE_SAFE' || state.cursor!==this.committedCursor)
      throw new RecoveryProtocolError('PROTOCOL_ERROR')
    this.onPublish(state,this.image(state))
  }
  quarantine(reason: string): void {this.run++;this.active=false;this.committedCursor=null;this.onQuarantine(reason)}
}
