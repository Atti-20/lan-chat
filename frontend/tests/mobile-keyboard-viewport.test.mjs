import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('keyboard viewport transitions re-anchor the mobile document after sending', async () => {
  const coordinator = await readFile(
    new URL('../src/components/chat/NavigationCoordinator.vue', import.meta.url),
    'utf8',
  )

  // iOS can change visualViewport after a composer loses focus.  Both the
  // focus transition and the viewport transition must reset the fixed chat
  // document, otherwise it stays translated above the visible screen.
  assert.match(coordinator, /window\.addEventListener\('focusin', keepMobileViewportAnchored\)/)
  assert.match(coordinator, /window\.addEventListener\('focusout', keepMobileViewportAnchored\)/)
  assert.match(coordinator, /window\.visualViewport\?\.addEventListener\('resize', scheduleViewportUpdate\)/)
  assert.match(coordinator, /updateViewport\(\)\s*\n\s*\/\/ Keyboard close[\s\S]*?keepMobileViewportAnchored\(\)/)
  assert.match(coordinator, /window\.scrollTo\(0, 0\)/)
  assert.match(coordinator, /document\.documentElement\.scrollTop = 0/)
  assert.match(coordinator, /document\.body\.scrollTop = 0/)
})

test('submitting while the iOS textarea keeps focus explicitly re-anchors the viewport', async () => {
  const [composer, workspace, chatView, coordinator] = await Promise.all([
    readFile(new URL('../src/components/chat/MessageComposer.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/chat/ChatWorkspace.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/chat/NavigationCoordinator.vue', import.meta.url), 'utf8'),
  ])

  // Pointer and Enter submission share submit(); no blur is required, so the
  // keyboard remains ready for consecutive messages while the page is reset.
  assert.match(composer, /composerSubmitted: \[\]/)
  assert.match(composer, /function submit\(\): void \{[\s\S]*?emit\('send',[\s\S]*?emit\('composerSubmitted'\)/)
  assert.match(composer, /onKeydown\([\s\S]*?submit\(\)/)
  assert.match(composer, /@click="submit"/)
  assert.match(workspace, /composerSubmitted: \[\]/)
  assert.match(workspace, /@composer-submitted="emit\('composerSubmitted'\)"/)
  assert.match(chatView, /const composerSubmitRevision = shallowRef\(0\)/)
  assert.match(chatView, /:composer-submit-revision="composerSubmitRevision"/)
  assert.match(chatView, /@composer-submitted="reanchorMobileViewportAfterComposerSubmit"/)
  assert.match(coordinator, /composerSubmitRevision\?: number/)
  assert.match(coordinator, /\(\) => props\.composerSubmitRevision,[\s\S]*?keepMobileViewportAnchored\(\)[\s\S]*?flush: 'post'/)
})

test('the iOS WebView does not double-apply UIKit and CSS safe-area insets', async () => {
  const controller = await readFile(
    new URL('../../apps/ios/ios/App/App/MeshXViewController.swift', import.meta.url),
    'utf8',
  )

  assert.match(controller, /webView\.scrollView\.contentInsetAdjustmentBehavior = \.never/)
  assert.match(controller, /webView\.scrollView\.automaticallyAdjustsScrollIndicatorInsets = false/)
  assert.match(controller, /webView\.bottomAnchor\.constraint\(equalTo: container\.keyboardLayoutGuide\.topAnchor\)/)
})
