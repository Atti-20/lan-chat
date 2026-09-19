import assert from 'node:assert/strict'
import test from 'node:test'
import {createTsLoader, plain} from './helpers/load-ts.mjs'

function harness() {
  let session = null
  const values = new Map(), events = []
  const load = createTsLoader({
    replacements: {
      'apps/web/src/utils/storage.ts': {readSession: () => session},
      'apps/web/src/composables/useToast.ts': {useToast: () => ({push() {}})},
      'apps/web/src/platform/desktopNodeSelection.ts': {activateDesktopNode: async () => {throw Error('unexpected node switch')}},
      'apps/web/src/platform/nativeBridge.ts': {nativeBridge: {runtime: () => 'web'}},
      'apps/web/src/platform/appNavigation.ts': {navigateToApp: () => {throw Error('unexpected navigation')}},
      'apps/web/src/platform/nodeContext.ts': {selectedNode: () => null},
    },
    globals: {
      URL,
      sessionStorage: {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key)},
      window: {location: {origin: 'https://mesh.test', pathname: '/chat'}, dispatchEvent: event => events.push(event)},
      CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options.detail}},
    },
  })
  return {...load('apps/web/src/platform/desktopNavigation.ts'), login: userId => {session = {token: 'fixture', userId}}, events}
}

const route = {kind: 'conversation', value: 'group:21', nodeOrigin: 'https://mesh.test', messageId: 'live', notificationOwner: 'https://mesh.test|7'}

test('notification arriving before login waits for the same owner before consumption', async () => {
  const h = harness()
  await h.handleNotificationNavigation(route)
  assert.equal(h.events.length, 0)
  assert.equal(h.pendingDesktopNavigation().messageId, 'live')
  h.login(7)
  assert.deepEqual(plain(h.claimNavigationForCurrentNode()), {...route, notification: true})
  assert.equal(h.claimNavigationForCurrentNode(), null)
})

test('a different account cannot consume a pending notification with matching conversation id', async () => {
  const h = harness()
  await h.handleNotificationNavigation(route)
  h.login(8)
  assert.equal(h.claimNavigationForCurrentNode(), null)
  assert.equal(h.pendingDesktopNavigation(), null)
  await h.handleNotificationNavigation({...route, messageId: 'other'})
  assert.equal(h.events.length, 0)
})

test('distinct messages in the same conversation do not suppress each other', async () => {
  const h = harness(); h.login(7)
  await h.handleNotificationNavigation(route)
  await h.handleNotificationNavigation({...route, messageId: 'second'})
  assert.equal(h.events.length, 2)
  assert.equal(h.pendingDesktopNavigation().messageId, 'second')
})
