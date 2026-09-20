import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/prepareUploadImage.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { prepareUploadImage } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('non-images remain byte-identical and cancellation stops before decoding', async () => {
  const file = new File(['data'], 'document.pdf', { type: 'application/pdf' })
  assert.equal(await prepareUploadImage(file), file)
  await assert.rejects(prepareUploadImage(file, AbortSignal.abort()), { name: 'AbortError' })
})

test('camera photos are bounded, preserve PNG format and release decoding resources', async t => {
  let width = 8000, height = 6000, closed = 0, revoked = 0, options
  t.mock.method(URL, 'revokeObjectURL', () => { revoked++ })
  t.mock.method(URL, 'createObjectURL', () => 'blob:test')
  const oldImage = globalThis.Image, oldBitmap = globalThis.createImageBitmap, oldDocument = globalThis.document
  globalThis.Image = class {
    naturalWidth = width; naturalHeight = height
    set src(value) { if (value) queueMicrotask(() => this.onload?.()) }
  }
  globalThis.createImageBitmap = async (_, input) => { options = input; return { close() { closed++ } } }
  globalThis.document = { createElement: () => ({ width: 0, height: 0, getContext: () => ({ drawImage() {} }), toBlob: (callback, type) => callback(new Blob(['encoded'], { type })) }) }
  t.after(() => { globalThis.Image = oldImage; globalThis.createImageBitmap = oldBitmap; globalThis.document = oldDocument })
  const original = new File(['original'], 'camera.jpg', { type: 'image/jpeg', lastModified: 123 })
  const result = await prepareUploadImage(original)
  assert.equal(result.name, 'camera-meshx.jpg')
  assert.equal(result.lastModified, 123)
  assert.equal(options.resizeWidth, 4096); assert.equal(options.resizeHeight, 3072)
  assert.equal(closed, 1); assert.equal(revoked, 1)
  assert.equal(await original.text(), 'original')
  const png = await prepareUploadImage(new File(['png'], 'alpha.png', { type: 'image/png' }))
  assert.equal(png.type, 'image/png')
  width = 1200; height = 800
  assert.equal(await prepareUploadImage(original), original)
  const heic = await prepareUploadImage(new File(['heic'], 'camera.heic', { type: 'image/heic' }))
  assert.equal(heic.type, 'image/jpeg')
  assert.equal(options.resizeWidth, 1200)
})
