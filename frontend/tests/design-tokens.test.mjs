import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import test from 'node:test'

const source = new URL('../src/', import.meta.url)
const mainCss = await readFile(new URL('assets/main.css', source), 'utf8')
const tokens = (css) => Object.fromEntries([...css.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
const light = tokens(mainCss.match(/:root\s*\{([^}]+)\}/s)[1])
const dark = { ...light, ...tokens(mainCss.match(/\[data-theme="dark"\]\s*\{([^}]+)\}/s)[1]) }

function luminance(hex) {
  const rgb = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((n) => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4)
  return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722
}

test('semantic text and filled actions remain readable in both themes', () => {
  for (const [theme, values] of Object.entries({ light, dark })) {
    const pairs = ['--ink', '--ink-soft', '--ink-faint', '--accent-text', '--success', '--warning', '--danger']
      .flatMap((fg) => ['--panel', '--fill', '--canvas'].map((bg) => [fg, bg]))
    pairs.push(...['--action-bg', '--success-bg', '--danger-bg'].map((bg) => ['--on-accent', bg]))
    for (const [fg, bg] of pairs) {
      const a = luminance(values[fg]), b = luminance(values[bg])
      const ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05)
      assert.ok(ratio >= 4.5, `${theme} ${fg}/${bg}: ${ratio.toFixed(2)}:1`)
    }
  }
})

async function sourceFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  return (await Promise.all(entries.map(async (entry) => {
    const url = new URL(entry.name + (entry.isDirectory() ? '/' : ''), dir)
    return entry.isDirectory() ? sourceFiles(url) : /\.(vue|css)$/.test(entry.name) ? [url] : []
  }))).flat()
}

test('component CSS cannot silently reference an undefined design token', async () => {
  const sheets = await Promise.all((await sourceFiles(source)).map(async (url) => {
    const text = await readFile(url, 'utf8')
    return {
      url,
      css: url.pathname.endsWith('.css') ? text : [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n'),
      dynamic: [...text.matchAll(/['"](--[\w-]+)['"]\s*:/g)].map((m) => m[1]),
    }
  }))
  const defined = new Set(sheets.flatMap(({ css, dynamic }) => [...dynamic, ...[...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1])]))
  const missing = []
  for (const { url, css } of sheets) {
    for (const match of css.matchAll(/var\((--[\w-]+)\s*([,)])/g)) {
      // Locally calculated dimensions must provide a fallback at their use site.
      if (!defined.has(match[1]) && match[2] !== ',') missing.push(`${url.pathname}: ${match[1]}`)
    }
  }
  assert.deepEqual(missing, [])
})
