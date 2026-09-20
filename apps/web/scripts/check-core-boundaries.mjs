import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, extname, isAbsolute, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const dependencyRules = {
  protocol: new Set(['protocol']),
  'domain-ts': new Set(['domain-ts', 'protocol']),
  'platform-ports': new Set(['platform-ports', 'domain-ts', 'protocol']),
}

function sourceFiles(directory) {
  if (!existsSync(directory)) return []
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? sourceFiles(path) : extname(path) === '.ts' ? [path] : []
  })
}

function within(path, directory) {
  const from = relative(directory, path)
  return from === '' || (!from.startsWith('..') && !isAbsolute(from))
}

/** AST, not a text substring allowlist: includes export-from/import-type/require/dynamic import. */
export function checkCoreBoundaries(repository = root) {
  const errors = []
  for (const [module, allowed] of Object.entries(dependencyRules)) {
    for (const path of sourceFiles(resolve(repository, 'packages', module, 'src'))) {
      const source = ts.createSourceFile(path, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true)
      const report = (node, reason) => {
        const location = source.getLineAndCharacterOfPosition(node?.getStart(source) ?? 0)
        errors.push(`${relative(repository, path)}:${location.line + 1}: ${reason}`)
      }
      const checkSpecifier = (node, specifier) => {
        if (!specifier || !(ts.isStringLiteral(specifier) || ts.isNoSubstitutionTemplateLiteral(specifier))) {
          report(node, 'Non-literal module loading cannot be checked in shared code')
          return
        }
        const name = specifier.text
        if (/\.(css|scss|sass|less|styl|dart)(?:[?#].*)?$/.test(name)) {
          report(node, `Shared code cannot import presentation styles or Flutter files: ${name}`)
          return
        }
        if (!name.startsWith('.')) {
          report(node, `Shared code cannot import runtime/package ${name}`)
          return
        }
        const target = resolve(dirname(path), name)
        if (![...allowed].some(dependency => within(target, resolve(repository, 'packages', dependency, 'src')))) {
          report(node, `Dependency direction violated: ${name}`)
        }
      }
      const browserGlobals = new Set(['window', 'document', 'navigator', 'indexedDB', 'localStorage', 'sessionStorage',
        'WebSocket', 'XMLHttpRequest', 'fetch', 'Worker', 'FileReader'])
      function visit(node) {
        if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier) checkSpecifier(node, node.moduleSpecifier)
        if (ts.isImportTypeNode(node)) checkSpecifier(node, ts.isLiteralTypeNode(node.argument) ? node.argument.literal : node.argument)
        if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) checkSpecifier(node, node.moduleReference.expression)
        if (ts.isCallExpression(node) && (node.expression.kind === ts.SyntaxKind.ImportKeyword
          || (ts.isIdentifier(node.expression) && ['require', 'eval', 'Function'].includes(node.expression.text)))) {
          if (ts.isIdentifier(node.expression) && node.expression.text !== 'require') report(node, 'Dynamic code execution cannot be checked in shared code')
          else checkSpecifier(node, node.arguments[0])
        }
        if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'Function') report(node, 'Dynamic code execution cannot be checked in shared code')
        if (ts.isIdentifier(node) && browserGlobals.has(node.text)) {
          // Property *names* on plain domain data are harmless; shorthand values,
          // globals and globalThis.window all still represent runtime access.
          const parent = node.parent
          const isPlainProperty = (ts.isPropertySignature(parent) || ts.isPropertyAssignment(parent)) && parent.name === node
          if (!isPlainProperty) report(node, `Browser implementation belongs in adapters: ${node.text}`)
        }
        if (ts.isElementAccessExpression(node) && ts.isIdentifier(node.expression)
          && ['globalThis', 'self'].includes(node.expression.text)) report(node, 'Computed global access cannot be checked in shared code')
        if (ts.isModuleDeclaration(node) && (node.flags & ts.NodeFlags.GlobalAugmentation)) report(node, 'Shared code must not add ambient runtime globals')
        ts.forEachChild(node, visit)
      }
      for (const reference of [...source.referencedFiles, ...source.typeReferenceDirectives, ...source.libReferenceDirectives]) {
        errors.push(`${relative(repository, path)}: reference directive is not allowed in shared code: ${reference.fileName}`)
      }
      visit(source)
    }
  }
  return errors
}

/** Compile every domain source and its transitive imports with ES only, plus the new narrow ports.
 * The legacy NativeBridge file includes AbortSignal in its existing file-save ABI;
 * it is checked for imports above but is not part of the platform-free core graph.
 * Generated REST multipart types use Blob for Web consumers; importing that
 * browser-oriented surface into domain is rejected by this no-DOM compilation.
 */
export function checkCoreTypes(repository = root) {
  const files = sourceFiles(resolve(repository, 'packages/domain-ts/src'))
  files.push(...['outbox.ts', 'realtime.ts'].map(name => resolve(repository, 'packages/platform-ports/src', name)).filter(existsSync))
  const program = ts.createProgram(files, {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler,
    lib: ['lib.es2022.d.ts'], types: [], strict: true, noEmit: true, skipLibCheck: false,
  })
  return ts.getPreEmitDiagnostics(program).map(diagnostic => {
    const location = diagnostic.file && diagnostic.start !== undefined
      ? `${relative(repository, diagnostic.file.fileName)}:${diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start).line + 1}: ` : ''
    return `${location}${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')}`
  })
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repository = process.argv[2] ? resolve(process.argv[2]) : root
  const errors = [...checkCoreBoundaries(repository), ...checkCoreTypes(repository)]
  if (errors.length) { process.stderr.write(errors.join('\n') + '\n'); process.exitCode = 1 }
  else process.stdout.write('PASS: shared import boundaries and ES-only Core/port typecheck\n')
}
