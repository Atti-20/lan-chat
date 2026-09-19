import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from '../node_modules/typescript/lib/typescript.js'

async function load(path, imports) {
  let source = await readFile(new URL(path, import.meta.url), 'utf8')
  for (const [original, replacement] of imports) source = source.replace(original, replacement)
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
}

test('iOS permission can be requested explicitly in foreground without prompting at launch', async () => {
  const module = await load('../src/platform/mobileNotifications.ts', [
    ["import { LocalNotifications } from '@capacitor/local-notifications'", `
      export const calls = [];
      export const state = { display: 'prompt', native: true, platform: 'ios' };
      const LocalNotifications = {
        checkPermissions: async () => ({ display: state.display }),
        requestPermissions: async () => { calls.push('request'); state.display = 'granted'; return { display: state.display }; },
        createChannel: async () => { calls.push('channel'); },
      };`],
    ["import { capacitorPlatform, isCapacitorRuntime } from './mobileRuntime'", `
      const capacitorPlatform = () => state.platform;
      const isCapacitorRuntime = () => state.native;`],
  ])
  assert.equal(await module.mobileNotificationPermission(), 'prompt')
  assert.equal(await module.initializeMobileNotification(), false)
  assert.deepEqual(module.calls, [])
  assert.equal(await module.initializeMobileNotification({ requestPermission: true }), true)
  assert.deepEqual(module.calls, ['request'])
  assert.equal(await module.mobileNotificationPermission(), 'granted')
  module.state.display = 'denied'
  assert.equal(await module.initializeMobileNotification({ requestPermission: true }), false)
  assert.deepEqual(module.calls, ['request'], 'denial must not repeatedly prompt')
  module.state.display = 'prompt-with-rationale'
  assert.equal(await module.mobileNotificationPermission(), 'prompt')
  module.state.native = false
  assert.equal(await module.mobileNotificationPermission(), 'unavailable')
  assert.equal(await module.initializeMobileNotification({ requestPermission: true }), false)
  module.state.native = true
  module.state.platform = 'android'
  assert.equal(await module.initializeMobileNotification(), true)
  assert.deepEqual(module.calls, ['request', 'request', 'channel'])
})

test('native appearance forwards both explicit themes only to the iOS shell', async () => {
  const module = await load('../src/platform/mobileAppearance.ts', [
    ["import { registerPlugin } from '@capacitor/core'", `
      export const calls = [];
      export const state = { native: true, platform: 'ios' };
      const registerPlugin = (name) => ({ setTheme: async (options) => calls.push({ name, ...options }) });`],
    ["import { capacitorPlatform, isCapacitorRuntime } from './mobileRuntime'", `
      const capacitorPlatform = () => state.platform;
      const isCapacitorRuntime = () => state.native;`],
  ])
  await module.syncMobileAppearance('dark')
  await module.syncMobileAppearance('light')
  assert.deepEqual(module.calls, [{ name: 'MeshXAppearance', mode: 'dark' }, { name: 'MeshXAppearance', mode: 'light' }])
  module.state.platform = 'android'
  await module.syncMobileAppearance('dark')
  module.state.native = false
  module.state.platform = 'ios'
  await module.syncMobileAppearance('dark')
  assert.equal(module.calls.length, 2)
})

test('native background state overrides a stale visible WebView without changing browser focus policy', async () => {
  const module = await load('../src/platform/appActivity.ts', [
    ["import { isCapacitorRuntime } from './mobileRuntime'", `
      export const state = { native: true, visible: 'visible', focused: true };
      const isCapacitorRuntime = () => state.native;
      const document = { get visibilityState() { return state.visible; }, hasFocus: () => state.focused };`],
  ])
  assert.equal(module.isAppForeground(), true)
  module.setNativeAppActive(false)
  assert.equal(module.isAppForeground(), false)
  module.setNativeAppActive(true)
  assert.equal(module.isAppForeground(), true)
  module.state.native = false
  module.setNativeAppActive(false)
  assert.equal(module.isAppForeground(), true)
  module.state.focused = false
  assert.equal(module.isAppForeground(), false)
  module.setNativeAppActive(null)
  module.state.native = true
  assert.equal(module.isAppForeground(), false)
})
