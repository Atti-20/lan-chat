import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'

export const repositoryRoot = fileURLToPath(new URL('../../../../', import.meta.url))

// Load real TS and local dependencies without a browser. Replace only side-effect
// boundaries; missing external dependencies fail rather than silently becoming mocks.
export function createTsLoader({ replacements = {}, externals = {}, globals = {} } = {}) {
  const cache = new Map()
  const substituted = new Map(Object.entries(replacements).map(([path, value]) => [resolve(repositoryRoot, path), value]))
  function load(path) {
    const absolute = resolve(repositoryRoot, path)
    if (substituted.has(absolute)) return substituted.get(absolute)
    if (cache.has(absolute)) return cache.get(absolute)
    const source = readFileSync(absolute, 'utf8')
    const js = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    } }).outputText
    const exports = {}
    cache.set(absolute, exports)
    runInNewContext(js, { exports, require(id) {
      if (id.startsWith('.')) return load(resolve(dirname(absolute), id.endsWith('.ts') ? id : `${id}.ts`))
      assert.ok(id in externals, `Unexpected external import ${id} in ${path}`)
      return externals[id]
    }, ...globals }, { filename: absolute })
    return exports
  }
  return load
}

export const plain = value => JSON.parse(JSON.stringify(value))
export const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
