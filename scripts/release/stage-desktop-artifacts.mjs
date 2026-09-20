import { createHash } from 'node:crypto'
import {
  copyFile,
  mkdir,
  readdir,
  readFile,
  stat,
  writeFile,
} from 'node:fs/promises'
import { basename, join, relative, resolve } from 'node:path'
import process from 'node:process'

const [platform, bundleRootArg, outputDirectoryArg, expectedVersion] =
  process.argv.slice(2)

if (
  !['linux', 'macos', 'windows'].includes(platform) ||
  !bundleRootArg ||
  !outputDirectoryArg ||
  !expectedVersion
) {
  throw new Error(
    'usage: stage-desktop-artifacts.mjs linux|macos|windows BUNDLE_ROOT OUTPUT_DIRECTORY VERSION',
  )
}

const bundleRoot = resolve(bundleRootArg)
const outputDirectory = resolve(outputDirectoryArg)
const outputFromBundle = relative(bundleRoot, outputDirectory)
if (
  outputDirectory === bundleRoot ||
  (!outputFromBundle.startsWith('..') && outputFromBundle !== '')
) {
  throw new Error('OUTPUT_DIRECTORY must not be inside BUNDLE_ROOT')
}

const suffixes = {
  linux: [
    '.deb',
    '.AppImage',
    '.AppImage.sig',
  ],
  macos: ['.dmg', '.app.tar.gz', '.app.tar.gz.sig'],
  windows: [
    '-setup.exe',
    '-setup.exe.sig',
    '.msi',
    '.msi.sig',
  ],
}[platform]

async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const results = []

  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      results.push(...(await walk(path)))
    } else if (entry.isFile()) {
      results.push(path)
    }
  }

  return results
}

function matchesSuffix(path) {
  return suffixes.some((suffix) => path.endsWith(suffix))
}

function stagedName(path) {
  if (platform !== 'macos') {
    return basename(path)
  }
  if (path.endsWith('.app.tar.gz.sig')) {
    return `MeshX_${expectedVersion}_universal.app.tar.gz.sig`
  }
  if (path.endsWith('.app.tar.gz')) {
    return `MeshX_${expectedVersion}_universal.app.tar.gz`
  }
  if (path.endsWith('.dmg')) {
    return `MeshX_${expectedVersion}_universal.dmg`
  }
  throw new Error(`Unsupported macOS artifact: ${path}`)
}

async function sha256(path) {
  const contents = await readFile(path)
  return createHash('sha256').update(contents).digest('hex')
}

const sourceFiles = (await walk(bundleRoot)).filter(matchesSuffix)
if (sourceFiles.length !== suffixes.length) {
  throw new Error(
    `Expected ${suffixes.length} ${platform} artifacts, found ${sourceFiles.length}:\n${sourceFiles.join('\n')}`,
  )
}

for (const suffix of suffixes) {
  const matches = sourceFiles.filter((path) => path.endsWith(suffix))
  if (matches.length !== 1) {
    throw new Error(
      `Expected exactly one ${suffix} artifact for ${platform}; found ${matches.length}`,
    )
  }
}

await mkdir(outputDirectory, { recursive: true })
const manifest = {
  platform,
  version: expectedVersion,
  artifacts: [],
}
const usedNames = new Set()

for (const sourcePath of sourceFiles.sort()) {
  const name = stagedName(sourcePath)
  if (usedNames.has(name)) {
    throw new Error(`Duplicate staged artifact name: ${name}`)
  }
  usedNames.add(name)

  const destinationPath = join(outputDirectory, name)
  await copyFile(sourcePath, destinationPath)
  const metadata = await stat(destinationPath)
  if (metadata.size <= 0) {
    throw new Error(`Staged artifact is empty: ${destinationPath}`)
  }

  manifest.artifacts.push({
    name,
    bytes: metadata.size,
    sha256: await sha256(destinationPath),
  })
}

await writeFile(
  join(outputDirectory, `verification-${platform}.json`),
  `${JSON.stringify(manifest, null, 2)}\n`,
)

for (const artifact of manifest.artifacts) {
  process.stdout.write(
    `${artifact.sha256}  ${artifact.name} (${artifact.bytes} bytes)\n`,
  )
}
