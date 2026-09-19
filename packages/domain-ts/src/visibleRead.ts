/** UI samples, not downloads or ACKs, establish contiguous read eligibility. */
export interface ReadRow { sequence: number; own: boolean }
export class VisibleReadTracker {
  private since = new Map<number, number>()
  private qualified = new Set<number>()
  private sampledAt: number | undefined
  private position: number
  private rows: ReadRow[]
  constructor(readonly scope: string, baseline: number, rows: readonly ReadRow[], private readonly completeThrough: number) {
    if(!Number.isSafeInteger(baseline) || baseline<0 || !Number.isSafeInteger(completeThrough) || completeThrough<0) throw new Error('INVALID_READ_PROOF')
    this.position=baseline;this.rows=[...rows].sort((a,b)=>a.sequence-b.sequence)
    const seen=new Set<number>()
    for(const row of this.rows) {
      if(!Number.isSafeInteger(row.sequence) || row.sequence<=0 || seen.has(row.sequence)) throw new Error('INVALID_READ_PROOF')
      seen.add(row.sequence)
    }
  }
  cancel(): void {this.since.clear();this.sampledAt=undefined}
  sample(scope: string, now: number, visible: ReadonlySet<number>, foreground: boolean): number {
    if(scope!==this.scope || !foreground || !Number.isFinite(now)) {this.cancel();return this.position}
    if(this.sampledAt!==undefined && (now<this.sampledAt || now-this.sampledAt>200)) this.since.clear()
    this.sampledAt=now
    for(const sequence of this.since.keys()) if(!visible.has(sequence)) this.since.delete(sequence)
    for(const row of this.rows) if(visible.has(row.sequence)) {
      const start=this.since.get(row.sequence) ?? now;this.since.set(row.sequence,start)
      if(now-start>=300) this.qualified.add(row.sequence)
    }
    for(const row of this.rows) {
      if(row.sequence<=this.position) continue
      if(row.sequence>this.position+1 && row.sequence-1>this.completeThrough) break
      if(!row.own && !this.qualified.has(row.sequence)) break
      this.position=row.sequence
    }
    return this.position
  }
}
