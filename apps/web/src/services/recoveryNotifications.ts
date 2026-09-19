import {RecoveryNotificationJournal,type NotificationEffect,type NotificationRouteProof} from '../../../../packages/domain-ts/src/notificationRecovery'
import type {RecoveryChatSession} from './recoveryChatSession'
export interface RecoveryNotificationPort {
  cancelAll():Promise<void>
  cancel(id:string):Promise<void>
  show(id:string,route:NotificationRouteProof):Promise<void>
}
/** Retains failed OS intents; acknowledges only in a subsequent atomic chat commit. */
export class RecoveryNotificationDispatcher {
  private running:Promise<void>|null=null
  constructor(private readonly session:RecoveryChatSession,private readonly port:RecoveryNotificationPort,
    private readonly mayShow:(route:NotificationRouteProof)=>boolean=()=>true) {}
  drain():Promise<void> {
    if(this.running) return this.running
    this.running=this.perform().finally(()=>{this.running=null})
    return this.running
  }
  private async perform():Promise<void> {
    while(this.session.safe && this.session.state && this.session.image) {
      const image=this.session.image.notificationRecovery,effect=image.effects[0]
      if(!effect) return
      const state=this.session.state
      if(effect.kind==='CANCEL_ALL') await this.port.cancelAll()
      else if(effect.kind==='CANCEL') await this.port.cancel(effect.messageId!)
      else {
        const route=image.routes.find(r=>r.messageId===effect.messageId)
        if(route && new RecoveryNotificationJournal(image).allows(state,route.messageId) && this.mayShow(route)) {
          await this.port.show(route.messageId,route)
          if(!this.session.safe || !this.session.state || !this.session.image
            || !new RecoveryNotificationJournal(this.session.image.notificationRecovery).allows(this.session.state,route.messageId)) {
            // Revoke/quarantine during an OS call must not leave its late alert active.
            await this.port.cancel(route.messageId)
            return
          }
        }
      }
      if(!this.session.safe || this.session.state!==state || !this.stillPending(effect)) return
      await this.session.acknowledgeNotification(effect.key)
    }
  }
  private stillPending(effect:NotificationEffect):boolean {
    return this.session.image?.notificationRecovery.effects.some(e=>e.key===effect.key && e.kind===effect.kind)===true
  }
}
