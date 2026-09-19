import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('a receiver submission is rendered as completed independently of global broadcast status', async () => {
  const sidebar = await source('../src/components/broadcasts/BroadcastSidebar.vue')

  assert.match(sidebar, /if \(broadcast\.currentUserConfirmedAt \|\| broadcast\.currentUserCompletedAt\) return 'COMPLETED'/)
  assert.match(sidebar, /currentUserConfirmedAt \? '我已完成' : '已完成'/)
  assert.match(sidebar, /\{ value: 'COMPLETED', label: '已完成' \}/)
})

test('the mobile broadcast detail keeps a safe native-sized back action and clear completion copy', async () => {
  const mobileWorkspace = await source('../src/components/broadcasts/MobileBroadcastWorkspace.vue')
  const completionPanel = await source('../src/components/broadcasts/BroadcastCompletionPanel.vue')

  assert.match(mobileWorkspace, /env\(safe-area-inset-top\)/)
  assert.match(mobileWorkspace, /env\(safe-area-inset-bottom\)/)
  assert.match(mobileWorkspace, /\.nav-back \{[^}]*min-width:\s*44px;[^}]*min-height:\s*44px;/s)
  assert.match(mobileWorkspace, /已从你的待处理列表移除/)
  assert.match(completionPanel, /return '提交完成结果'/)
})
