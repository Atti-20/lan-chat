import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/services/conversationSummaryState.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const {
  applyConversationMessage,
  applyConversationRead,
  conversationReadRequiresSnapshot,
  indexConversationSummaries,
  removeConversationSummary,
} = await import(moduleUrl)

function snapshot(overrides = {}) {
  return {
    conversationId: 'private:7:9',
    kind: 'private',
    targetId: 9,
    lastSequence: 12,
    lastReadSequence: 8,
    unreadCount: 3,
    lastMessage: 'server truth',
    lastMessageType: 'text',
    lastMessageAt: '2026-07-26T03:00:00',
    pinned: false,
    muted: false,
    ...overrides,
  }
}

test('reloaded and cross-device state is replaced by the backend snapshot', () => {
  const firstPage = indexConversationSummaries([snapshot({ unreadCount: 3 })])
  const refreshedPage = indexConversationSummaries([
    snapshot({ lastReadSequence: 12, unreadCount: 0 }),
  ])

  assert.equal(firstPage['private:7:9'].unreadCount, 3)
  assert.equal(refreshedPage['private:7:9'].lastReadSequence, 12)
  assert.equal(refreshedPage['private:7:9'].unreadCount, 0)
})

test('a read event from another device applies the exact committed unread state', () => {
  const current = indexConversationSummaries([snapshot()])
  const next = applyConversationRead(current, {
    conversationId: 'private:7:9',
    readerId: 7,
    currentUserId: 7,
    lastSequence: 12,
    lastReadSequence: 11,
    unreadCount: 1,
  })

  assert.equal(next['private:7:9'].lastReadSequence, 11)
  assert.equal(next['private:7:9'].unreadCount, 1)
})

test('a delayed read event cannot overwrite a newer unread message', () => {
  const current = applyConversationMessage(
    indexConversationSummaries([snapshot({
      lastSequence: 15,
      lastReadSequence: 10,
      unreadCount: 5,
    })]),
    {
      conversationId: 'private:7:9',
      sequence: 16,
      fromUserId: 9,
      currentUserId: 7,
      selected: false,
    },
  )
  const delayedRead = {
    conversationId: 'private:7:9',
    readerId: 7,
    currentUserId: 7,
    lastSequence: 15,
    lastReadSequence: 10,
    unreadCount: 5,
  }

  assert.equal(conversationReadRequiresSnapshot(current, delayedRead), true)
  assert.strictEqual(applyConversationRead(current, delayedRead), current)
  assert.equal(current['private:7:9'].lastSequence, 16)
  assert.equal(current['private:7:9'].unreadCount, 6)
})

test('SYNC messages already represented by a snapshot do not double unread', () => {
  const current = indexConversationSummaries([snapshot()])
  const duplicate = applyConversationMessage(current, {
    conversationId: 'private:7:9',
    sequence: 10,
    fromUserId: 9,
    currentUserId: 7,
    content: 'already counted',
    contentType: 'text',
    selected: false,
  })
  const genuinelyNew = applyConversationMessage(duplicate, {
    conversationId: 'private:7:9',
    sequence: 13,
    fromUserId: 9,
    currentUserId: 7,
    content: 'new after snapshot',
    contentType: 'text',
    selected: false,
  })

  assert.equal(duplicate['private:7:9'].unreadCount, 3)
  assert.equal(genuinelyNew['private:7:9'].unreadCount, 4)
  assert.equal(genuinelyNew['private:7:9'].lastSequence, 13)
})

test('leaving a group or temporary room removes its unread state', () => {
  for (const conversationId of ['group:2', 'temporary:3']) {
    const current = indexConversationSummaries([
      snapshot({
        conversationId,
        kind: conversationId.startsWith('group') ? 'group' : 'temporary',
        targetId: Number(conversationId.split(':')[1]),
      }),
    ])
    const next = removeConversationSummary(current, conversationId)
    assert.equal(next[conversationId], undefined)
  }
})

test('burning an old message keeps the newer conversation preview', () => {
  const current = indexConversationSummaries([snapshot()])
  const next = applyConversationMessage(current, {
    conversationId: 'private:7:9',
    sequence: 5,
    fromUserId: 9,
    currentUserId: 7,
    content: '消息已焚毁',
    contentType: 'text',
    createdAt: '2026-07-26T01:00:00',
    selected: true,
    forcePreview: true,
  })

  assert.equal(next['private:7:9'].lastMessage, 'server truth')
  assert.equal(next['private:7:9'].lastMessageAt, '2026-07-26T03:00:00')
  assert.equal(next['private:7:9'].lastSequence, 12)
})

test('burning the latest message replaces the preview in place', () => {
  const current = indexConversationSummaries([snapshot()])
  const next = applyConversationMessage(current, {
    conversationId: 'private:7:9',
    sequence: 12,
    fromUserId: 9,
    currentUserId: 7,
    content: '消息已焚毁',
    contentType: 'text',
    createdAt: '2026-07-26T03:00:00',
    selected: true,
    forcePreview: true,
  })

  assert.equal(next['private:7:9'].lastMessage, '消息已焚毁')
  assert.equal(next['private:7:9'].lastSequence, 12)
  assert.equal(next['private:7:9'].unreadCount, 3)
})
