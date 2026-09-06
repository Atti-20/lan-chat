import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('the native desktop chat shell uses global full-bleed rules', async () => {
  const [globalCss, chatView, app] = await Promise.all([
    source('../src/assets/main.css'),
    source('../src/views/ChatView.vue'),
    source('../src/App.vue'),
  ])

  // This rule deliberately lives outside ChatView's scoped <style>. Vue used
  // to compile a partial :global selector to html only, reviving the 20px
  // browser-card margin in native macOS and Windows windows.
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-page\s*\{[^}]*height:\s*100vh;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-page\s*\{[^}]*max-height:\s*100vh;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-page\s*\{[^}]*padding:\s*0;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*height:\s*100%;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*max-height:\s*100%;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*border-radius:\s*0;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*box-shadow:\s*none;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*margin:\s*0;/s)
  assert.match(globalCss, /html\[data-runtime='tauri'\] \.chat-shell\s*\{[^}]*border:\s*0;/s)
  assert.doesNotMatch(chatView, /:global\(html\[data-runtime='tauri'\]/)
  assert.match(app, /document\.documentElement\.dataset\.runtime = nativeBridge\.runtime\(\)/)
})

test('bridge selection requires injected native runtime capabilities', async () => {
  const bridge = await source('../src/platform/nativeBridge.ts')
  assert.match(bridge, /import \{ isTauri \} from '@tauri-apps\/api\/core'/)
  assert.match(bridge, /tauri: isTauriRuntime\(\)/)
  assert.doesNotMatch(bridge, /import\.meta\.env\.MODE === 'desktop'/)
  assert.doesNotMatch(bridge, /window\.location\.(?:protocol|hostname)/)

  const runtimeSource = await source('../src/platform/runtimeKind.ts')
  const runtimeCompiled = ts.transpileModule(runtimeSource, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText
  const { resolveRuntimeKind } = await import(
    `data:text/javascript;base64,${Buffer.from(runtimeCompiled).toString('base64')}`,
  )

  // A desktop build opened in an ordinary browser has no Tauri capability and
  // must keep the web bridge. Real Tauri injection wins, while Capacitor stays
  // isolated to mobile runtimes.
  assert.equal(resolveRuntimeKind({ tauri: false, capacitor: false }), 'web')
  assert.equal(resolveRuntimeKind({ tauri: true, capacitor: false }), 'tauri')
  assert.equal(resolveRuntimeKind({ tauri: false, capacitor: true }), 'capacitor')
})
