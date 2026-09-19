import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('navigation badges stay fixed, circular, and centered for every count', async () => {
  const rail = await source('../src/components/chat/AppRail.vue')
  const badgeRule = rail.match(/\.rail-badge \{([\s\S]*?)\n\}/)?.[1] || ''

  assert.match(badgeRule, /width: 20px;/)
  assert.match(badgeRule, /height: 20px;/)
  assert.match(badgeRule, /padding: 0;/)
  assert.match(badgeRule, /place-items: center;/)
  assert.match(badgeRule, /box-sizing: border-box;/)
  assert.match(badgeRule, /border-radius: 50%;/)
  assert.match(badgeRule, /font-variant-numeric: tabular-nums;/)
  assert.match(badgeRule, /white-space: nowrap;/)
  assert.doesNotMatch(badgeRule, /min-width:/)
})
