import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import {createTsLoader} from './helpers/load-ts.mjs'
const {VisibleReadTracker}=createTsLoader()('packages/domain-ts/src/visibleRead.ts')
const fixture=JSON.parse(readFileSync(new URL('../../../contracts/test-vectors/recovery-visible-read.json',import.meta.url)))
for(const c of fixture.cases) test(c.name,()=>{
  const tracker=new VisibleReadTracker('owner',c.baseline,c.rows,c.completeThrough)
  assert.deepEqual(c.samples.map(s=>tracker.sample(s.scope,s.now,new Set(s.visible),s.foreground)),c.expected)
})
