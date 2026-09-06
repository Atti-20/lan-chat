import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('mention rows preload and render concrete read and expected counts for their still-present sender', async () => {
  const thread = await source('../src/components/chat/MessageThread.vue')
  const chat = await source('../src/composables/useChat.ts')

  assert.match(thread, /const mentionReceiptCache = shallowRef<Map<string, MentionReadReceipt>>\(new Map\(\)\)/)
  assert.match(thread, /function requestMentionReceipt\(message: ChatMessage, force = false\)/)
  assert.match(thread, /const mentionReceiptMessages = computed\(\(\) => props\.messages\.filter\(canShowMentionReadState\)\)/)
  assert.match(thread, /member\.role >= 0/)
  assert.match(thread, /void requestMentionReceipt\(message, refreshRequested\)/)
  assert.match(thread, /targets\?\.toUpperCase\(\) === 'ALL'/)
  assert.match(chat, /mentionUserIds: typeof entry\.payload\.mentionUserIds === 'string'/)
  assert.match(chat, /case 'MENTION_RECEIPT_CHANGED':/)
  assert.match(chat, /const mentionReceiptRefreshRevision = shallowRef\(0\)/)
  assert.match(thread, /return receipt \? `已读 \$\{receipt\.readCount\} \/ 应读 \$\{receipt\.expectedCount\}` : ''/)
  assert.match(thread, /v-if="canShowMentionReadState\(message\) && mentionReadReceiptFor\(message\)"/)
  assert.match(thread, /@selectstart\.prevent/)
  assert.match(thread, /\.message-bubble--mention-receipt \{ -webkit-touch-callout: none; -webkit-user-select: none; user-select: none; \}/)
  assert.match(thread, /if \(pending && !force\) return pending\.promise/)
  assert.match(thread, /const mentionReceiptFetchVersions = new Map<string, number>\(\)/)
  assert.doesNotMatch(thread, /return '已读 \/ 应读'/)
})

test('mention selection keeps the visible tokens aligned with the metadata snapshot', async () => {
  const composer = await source('../src/components/chat/MessageComposer.vue')

  assert.match(composer, /function removeMentionToken\(token: string\): void/)
  assert.match(composer, /removeMentionToken\('@所有人'\)/)
  assert.match(composer, /else removeMentionToken\(`@\$\{member\.nickname\}`\)/)
  assert.match(composer, /watch\(canMentionAll, \(allowed\) =>/)
  assert.match(composer, /if \(mentionAll\.value && !canMentionAll\.value\) clearMentionAll\(\)/)
})

test('the receipt detail sheet is titled 接受详情', async () => {
  const sheet = await source('../src/components/chat/MentionReadReceiptSheet.vue')

  assert.match(sheet, /<h2 id="mention-receipt-title">接受详情<\/h2>/)
  assert.match(sheet, /aria-label="关闭接受详情"/)
  assert.match(sheet, /\{\{ receipt\.readCount \}\} \/ \{\{ receipt\.expectedCount \}\} 人已读/)
})
