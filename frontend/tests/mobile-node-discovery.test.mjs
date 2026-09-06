import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from '../node_modules/typescript/lib/typescript.js'

test('mobile discovery preserves mDNS source and requires the selected origin to match', async () => {
  const source = await readFile(new URL('../src/composables/useNodeDiscovery.ts', import.meta.url), 'utf8')
  // Execute the production composable; replace only its runtime boundaries.
  const parsed = ts.createSourceFile('useNodeDiscovery.ts', source, ts.ScriptTarget.Latest, true)
  const body = parsed.statements.filter((node) => !ts.isImportDeclaration(node))
    .map((node) => node.getText(parsed)).join('\n')
  const prelude = `
    const ref = value => ({ value }), shallowRef = ref, readonly = value => value;
    const computed = getter => ({ get value() { return getter() } });
    const onMounted = () => {}, onBeforeUnmount = () => {};
    export const state = { origin: 'http://192.168.0.100:18080', switched: null };
    const selectedNode = () => ({ nodeId: 'node-1', origin: state.origin });
    const nativeBridge = { runtime: () => 'capacitor', discoverNodeOrigins: async () => ['http://192.168.0.100:18081'] };
    const isCapacitorRuntime = () => true, isNativeNodeRuntime = () => true;
    const verifyMobileNode = async origin => ({ nodeId: 'node-1', nodeName: 'Node', apiOrigin: origin, appUrl: origin + '/app/', source: 'MANUAL', health: 'HEALTHY' });
    const activateDesktopNode = async node => { state.switched = node.apiOrigin; return true; };
    const navigateToApp = () => {};
  `
  const compiled = ts.transpileModule(prelude + body, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
  const discovery = module.useNodeDiscovery()
  await discovery.refresh()
  assert.equal(discovery.nodes.value.length, 1)
  assert.equal(discovery.nodes.value[0].source, 'MDNS')
  assert.equal(discovery.nodes.value[0].current, false)
  await discovery.openNode(discovery.nodes.value[0])
  assert.equal(module.state.switched, 'http://192.168.0.100:18081')
  module.state.origin = 'http://192.168.0.100:18081'
  assert.equal(discovery.nodes.value[0].current, true)
  module.state.switched = null
  await discovery.openNode(discovery.nodes.value[0])
  assert.equal(module.state.switched, null)
})
