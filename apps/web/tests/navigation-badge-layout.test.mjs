import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('navigation badges use the shared tokenized component and expand for multi-digit counts', async () => {
  const [rail, sidebar, badge, tokenSource] = await Promise.all([
    source('../src/components/chat/AppRail.vue'),
    source('../src/components/chat/ConversationSidebar.vue'),
    source('../src/components/base/UiBadge.vue'),
    source('../../../packages/design-tokens/tokens.json'),
  ])
  const tokens = JSON.parse(tokenSource)

  assert.match(rail, /import UiBadge/)
  assert.match(sidebar, /import UiBadge/)
  assert.match(sidebar, /messageFilter = shallowRef<'all' \| 'unread'>\('all'\)/)
  assert.match(sidebar, /filter\(\(conversation\) => \(conversation\.unreadCount \?\? 0\) > 0\)/)
  assert.doesNotMatch(sidebar, /unreadCount\s*=\s*0/)
  assert.match(badge, /min-width:\s*var\(--mx-component-badge-min-size\)/)
  assert.match(badge, /min-height:\s*var\(--mx-component-badge-min-size\)/)
  assert.match(badge, /padding-inline:\s*var\(--mx-component-badge-padding-inline\)/)
  assert.match(badge, /place-items:\s*center;/)
  assert.match(badge, /border-radius:\s*var\(--mx-shape-radius-pill\)/)
  assert.match(badge, /font-variant-numeric:\s*tabular-nums;/)
  assert.match(badge, /white-space:\s*nowrap;/)
  assert.equal(tokens.tokens['component.badge.min-size'].light.ref, 'tokens.size.icon.medium')
  assert.equal(tokens.tokens['component.badge.padding-inline'].light.ref, 'tokens.spacing.1')

  // 1 位数字保持紧凑，10 / 99 / 99+ 可以横向增长。
  assert.doesNotMatch(badge, /(?:^|[;\s])width:\s*20px;/m,)
  assert.doesNotMatch(badge, /(?:^|[;\s])height:\s*20px;/m,)
})

test('icon-only controls use the shared accessible foundation and Composer keeps both attachment entries', async () => {
  const [iconButton, sidebar, composer] = await Promise.all([
    source('../src/components/base/UiIconButton.vue'),
    source('../src/components/chat/ConversationSidebar.vue'),
    source('../src/components/chat/MessageComposer.vue'),
  ])

  assert.match(iconButton, /:aria-label="label"/)
  assert.match(iconButton, /:disabled="disabled \|\| loading"/)
  assert.match(iconButton, /component-icon-button-size/)
  assert.match(sidebar, /<UiIconButton/)
  assert.match(composer, /<UiIconButton[\s\S]*name="send"/)
  assert.match(composer, /chooseFile\(imageRef\)/)
  assert.match(composer, /chooseFile\(fileRef\)/)
})
