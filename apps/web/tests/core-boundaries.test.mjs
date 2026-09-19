import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { checkCoreBoundaries } from '../scripts/check-core-boundaries.mjs'

function fixture(source, run) {
  const root = mkdtempSync(resolve(tmpdir(), 'MeshX shared boundary '))
  const path = resolve(root, 'packages/domain-ts/src')
  mkdirSync(path, { recursive: true })
  writeFileSync(resolve(path, 'example.ts'), source)
  try { return run(root) } finally { rmSync(root, { recursive: true, force: true }) }
}

test('shared imports reject static, dynamic, require, export and import-type runtime dependencies', () => {
  for (const source of [
    "import { ref } from 'vue'", "export * from '@tauri-apps/api/core'", "void import('@tauri-apps/api/core')",
    "const x = require('vue')", "import v = require('vue')", "type T = import('vue').Ref<string>",
    "export * from '../../../apps/web/src/platform/nativeBridge'", 'void import(`vue`)',
    "const moduleName = 'vue'; void import(moduleName)",
  ]) fixture(source, root => assert.ok(checkCoreBoundaries(root).length, source))
})

test('core runtime checks cover aliases, optional access, session storage and computed globals', () => {
  for (const source of ['const storage = sessionStorage', 'navigator?.onLine', 'const { localStorage: storage } = globalThis',
    'globalThis["indexedDB"]', 'new WebSocket("ws://example")', 'const request = fetch', 'new Function("return window")']) {
    fixture(source, root => assert.ok(checkCoreBoundaries(root).length, source))
  }
})

test('core rejects design implementations and presentation files even inside its own directory', () => {
  for (const source of [
    "import '../../design-tokens/tokens.json'", "import './styles.css'", "export * from './styles.scss'",
    "void import('./theme.dart')", "void import('./styles.css?inline')",
  ]) fixture(source, root => assert.ok(checkCoreBoundaries(root).length, source))
})

test('comments and plain domain field names do not become fake runtime imports', () => {
  fixture("// import { ref } from 'vue'\nconst example = \"import('vue')\"; interface Log { document: string }", root => {
    assert.deepEqual(checkCoreBoundaries(root), [])
  })
})

test('real boundary command fails for a forbidden import and for DOM-only model types', () => {
  const script = fileURLToPath(new URL('../scripts/check-core-boundaries.mjs', import.meta.url))
  for (const source of ["import { ref } from 'vue'", 'export interface Bad { blob: Blob }', 'const timers = globalThis.setTimeout']) {
    fixture(source, root => {
      const result = spawnSync(process.execPath, [script, root], { encoding: 'utf8' })
      assert.equal(result.status, 1, result.stderr)
      assert.match(result.stderr, /example\.ts:/)
      assert.match(result.stderr, /Shared code|Cannot find|does not exist|no index signature/)
    })
  }
  fixture('export const increment = (value: number): number => value + 1', root => {
    assert.match(execFileSync(process.execPath, [script, root], { encoding: 'utf8' }), /PASS/)
  })
})
