import {recoveryMessageVisible,recoveryDecimal,RecoveryProtocolError,type RecoveryState} from './recovery'
export interface NotificationRouteProof {messageId:string;conversationId:string;objectVersion:string;accessVersion:string;streamEpoch:string}
export interface NotificationEffect {key:string;kind:'SHOW'|'CANCEL'|'CANCEL_ALL';messageId?:string}
export interface NotificationJournalImage {version:1;routes:NotificationRouteProof[];effects:NotificationEffect[]}
const fail=():never=>{throw new RecoveryProtocolError('UNSUPPORTED_SECURE_STATE')}
const id=(v:unknown):v is string=>typeof v==='string' && v.length>0 && v.length<=256
/** Body-free routes and OS intents share the chat generation; OS effects run only after commit. */
export class RecoveryNotificationJournal {
  private routes=new Map<string,NotificationRouteProof>()
  private effects=new Map<string,NotificationEffect>()
  constructor(raw?: unknown) {
    if(raw===undefined) {this.effects.set('migration',{key:'migration',kind:'CANCEL_ALL'});return}
    if(!raw || typeof raw!=='object' || Array.isArray(raw)) return fail()
    const value=raw as Record<string,unknown>
    if(Object.keys(value).some(k=>!['version','routes','effects'].includes(k)) || value.version!==1 || !Array.isArray(value.routes) || !Array.isArray(value.effects)) return fail()
    for(const r of value.routes) {
      if(!r || typeof r!=='object' || Object.keys(r).sort().join(',')!=='accessVersion,conversationId,messageId,objectVersion,streamEpoch'
        || !id(r.messageId) || !id(r.conversationId) || !id(r.streamEpoch) || this.routes.has(r.messageId)) return fail()
      recoveryDecimal(r.objectVersion,false);recoveryDecimal(r.accessVersion,false)
      this.routes.set(r.messageId,{...r})
    }
    for(const e of value.effects) {
      if(!e || typeof e!=='object' || !id(e.key) || this.effects.has(e.key) || !['SHOW','CANCEL','CANCEL_ALL'].includes(e.kind)
        || Object.keys(e).some(k=>!['key','kind','messageId'].includes(k)) || (e.kind!=='CANCEL_ALL' && !id(e.messageId))
        || (e.kind==='SHOW' && !this.routes.has(e.messageId))) return fail()
      this.effects.set(e.key,{...e})
    }
  }
  reconcile(state: RecoveryState, liveMessageIds: readonly string[]=[]): void {
    const matches=(r:NotificationRouteProof)=>state.context.streamEpoch===r.streamEpoch
      && state.messages.get(r.messageId)?.state==='NORMAL' && state.messages.get(r.messageId)?.conversationId===r.conversationId
      && state.messages.get(r.messageId)?.objectVersion===r.objectVersion && state.access.get(r.conversationId)?.readAllowed===true
      && state.access.get(r.conversationId)?.accessVersion===r.accessVersion && !state.rebuild.has(r.conversationId)
    for(const [id,r] of this.routes) if(!matches(r)) {
      this.routes.delete(id);this.effects.delete(`show:${id}`)
      this.effects.set(`cancel:${id}`,{key:`cancel:${id}`,kind:'CANCEL',messageId:id})
    }
    for(const id of liveMessageIds) {
      if(this.routes.has(id)) continue
      const message=state.messages.get(id),access=message && state.access.get(message.conversationId)
      if(message?.state!=='NORMAL' || !access?.readAllowed || state.rebuild.has(message.conversationId)) continue
      this.routes.set(id,{messageId:id,conversationId:message.conversationId,objectVersion:message.objectVersion,accessVersion:access.accessVersion,streamEpoch:state.context.streamEpoch})
      this.effects.set(`show:${id}`,{key:`show:${id}`,kind:'SHOW',messageId:id})
    }
  }
  allows(state:RecoveryState,id:string):boolean {
    const route=this.routes.get(id)
    return Boolean(route && recoveryMessageVisible(state,id) && state.context.streamEpoch===route.streamEpoch
      && state.messages.get(id)?.conversationId===route.conversationId && state.messages.get(id)?.objectVersion===route.objectVersion && state.access.get(route.conversationId)?.accessVersion===route.accessVersion)
  }
  acknowledge(key:string):void {this.effects.delete(key)}
  image():NotificationJournalImage {return {version:1,routes:[...this.routes.values()].map(r=>({...r})),effects:[...this.effects.values()].map(e=>({...e}))}}
}
