// Structural regression tests; browser/device effects require separate runs.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
const root = new URL('../../../', import.meta.url)
const read = (path) => readFileSync(new URL(path, root), 'utf8')

test('floating rail measures current geometry rather than fixed active*62 offsets', () => {
  const rail = read('apps/web/src/components/chat/AppRail.vue')
  assert.match(rail, /useGlassChromeMetrics\(railElement, 'navigation'\)/)
  assert.match(rail, /--mx-lens-x/)
  assert.doesNotMatch(rail, /var\(--active-index\)\s*\*\s*62px/)
})
test('tail reservation is on scroll content, not the opaque list parent', () => {
  const sidebar = read('apps/web/src/components/chat/ConversationSidebar.vue')
  assert.match(sidebar, /padding-bottom:\s*var\(--mx-runtime-nav-occlusion/)
  assert.doesNotMatch(sidebar, /padding-bottom:\s*calc\(88px \+ env\(safe-area-inset-bottom\)\);/)
})
test('chrome observers and listeners have teardown', () => {
  const metrics = read('apps/web/src/composables/useGlassChromeMetrics.ts')
  for (const item of ['ResizeObserver', 'disconnect()', 'cancelAnimationFrame(frame)',
    "removeEventListener('resize', schedule)", "removeProperty('--mx-runtime-nav-occlusion')"]) assert.ok(metrics.includes(item), item)
})
test('the workspace has an extreme-size flow fallback', () => {
  assert.match(read('apps/web/src/components/chat/ChatWorkspace.vue'), /chrome-in-flow/)
  assert.match(read('apps/web/src/composables/useGlassChromeMetrics.ts'), /top \+ bottom > height - 96/)
})
test('attachment menu retains original image and file input paths', () => {
  const composer = read('apps/web/src/components/chat/MessageComposer.vue')
  assert.match(composer, /details ref="attachmentMenu"/)
  assert.match(composer, /chooseFile\(imageRef\)/)
  assert.match(composer, /chooseFile\(fileRef\)/)
  assert.match(composer, /event\.isComposing/)
  assert.match(composer, /removeEventListener\('keydown', escapeAttachmentMenu, true\)/)
})
test('glass supports contrast and unsupported browser fallbacks', () => {
  for (const file of ['AppRail.vue', 'ChatWorkspace.vue']) {
    const source = read('apps/web/src/components/chat/' + file)
    assert.match(source, /prefers-reduced-transparency/)
    assert.match(source, /forced-colors/)
    assert.match(source, /@supports not/)
  }
})
test('glass dimensions and colors use the original versioned token source', () => {
  const tokens = JSON.parse(read('packages/design-tokens/tokens.json'))
  assert.equal(tokens.schemaVersion, 2)
  assert.ok(tokens.tokens['component.glass.control-size'])
  assert.ok(tokens.tokens['color.glass.readable'])
  assert.ok(tokens.aliases['ink'])
})
