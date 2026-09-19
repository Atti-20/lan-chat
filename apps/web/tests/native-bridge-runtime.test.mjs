import assert from 'node:assert/strict'
import { execFile as execFileCallback } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import test from 'node:test'

const execFile = promisify(execFileCallback)
const frontendRoot = fileURLToPath(new URL('..', import.meta.url))

// Load the real module in a new process for every host shape. This keeps Vite's
// module cache and Capacitor's plugin registry from leaking one host signal into
// another, while exercising the exported nativeBridge rather than just its
// pure selection helper.
const bridgeProbe = `
  import { createServer } from 'vite'

  const signals = JSON.parse(process.argv[1])
  globalThis.window = {
    location: { protocol: 'http:', hostname: 'localhost' },
    navigator: { platform: 'MeshX test', userAgent: 'MeshX test' },
    Capacitor: {
      isNativePlatform: () => signals.capacitor,
      getPlatform: () => signals.capacitor ? 'android' : 'web',
    },
  }
  globalThis.isTauri = signals.tauri

  const server = await createServer({
    configFile: false,
    root: process.cwd(),
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'error',
  })
  try {
    const { nativeBridge } = await server.ssrLoadModule('/src/platform/nativeBridge.ts')
    process.stdout.write(nativeBridge.runtime())
  } finally {
    await server.close()
  }
`

async function runtimeFor(signals) {
  const { stdout, stderr } = await execFile(
    process.execPath,
    ['--input-type=module', '-e', bridgeProbe, JSON.stringify(signals)],
    { cwd: frontendRoot },
  )
  assert.equal(stderr, '')
  return stdout.trim()
}

test('nativeBridge is selected from real injected host capabilities', async () => {
  assert.equal(await runtimeFor({ tauri: false, capacitor: false }), 'web')
  assert.equal(await runtimeFor({ tauri: true, capacitor: false }), 'tauri')
  assert.equal(await runtimeFor({ tauri: false, capacitor: true }), 'capacitor')
  assert.equal(await runtimeFor({ tauri: true, capacitor: true }), 'tauri')
})
