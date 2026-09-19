import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourceUrl = new URL('../src/platform/nodeAddressPolicy.ts', import.meta.url)
const source = await readFile(sourceUrl, 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
const { isPrivateLanHost, parseVerifiableNodeAddress } = await import(moduleUrl)

test('loopback, private IPv4, link-local, and mDNS hosts count as LAN', () => {
  for (const host of [
    'localhost', 'app.localhost', '127.0.0.1', '10.0.0.5', '172.16.0.1',
    '172.31.255.254', '192.168.1.20', '169.254.10.1', 'meshx-node.local',
    '[::1]', '::1', 'fe80::1', 'fd12:3456::1',
  ]) {
    assert.equal(isPrivateLanHost(host), true, host)
  }
})

test('public and malformed hosts are rejected as LAN targets', () => {
  for (const host of [
    'example.com', '8.8.8.8', '172.32.0.1', '172.15.0.1', '193.168.1.1',
    '2001:db8::1', '10.0.0.256', '', 'evil.local.example.com',
  ]) {
    assert.equal(isPrivateLanHost(host), false, host)
  }
})

test('cleartext HTTP node addresses outside the LAN are refused', () => {
  assert.throws(
    () => parseVerifiableNodeAddress('http://chat.example.com:8080'),
    /HTTP 明文节点仅允许/,
  )
  assert.throws(
    () => parseVerifiableNodeAddress('http://8.8.8.8'),
    /HTTP 明文节点仅允许/,
  )
})

test('LAN HTTP and any HTTPS node addresses are accepted', () => {
  assert.equal(parseVerifiableNodeAddress('http://192.168.1.20:8080').origin, 'http://192.168.1.20:8080')
  assert.equal(parseVerifiableNodeAddress('http://[fe80::1]:8080').hostname, '[fe80::1]')
  assert.equal(parseVerifiableNodeAddress('https://chat.example.com').origin, 'https://chat.example.com')
})

test('credentials or non-HTTP schemes are still rejected', () => {
  assert.throws(() => parseVerifiableNodeAddress('ftp://192.168.1.20'), /有效的 HTTP/)
  assert.throws(() => parseVerifiableNodeAddress('http://user:pass@192.168.1.20'), /有效的 HTTP/)
})
