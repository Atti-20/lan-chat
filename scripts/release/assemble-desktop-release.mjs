import { createHash } from 'node:crypto'
import {
  copyFile,
  mkdir,
  readdir,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises'
import { homedir } from 'node:os'
import { basename, join, parse, relative, resolve } from 'node:path'
import process from 'node:process'

const [
  candidatesDirectoryArg,
  outputDirectoryArg,
  expectedVersion,
  releaseTag,
  repository,
] = process.argv.slice(2)

if (
  !candidatesDirectoryArg ||
  !outputDirectoryArg ||
  !expectedVersion ||
  !releaseTag ||
  !repository
) {
  throw new Error(
    'usage: assemble-desktop-release.mjs CANDIDATES OUTPUT VERSION TAG OWNER/REPOSITORY',
  )
}
if (releaseTag !== `v${expectedVersion}`) {
  throw new Error(
    `Release tag ${releaseTag} does not match version v${expectedVersion}`,
  )
}
if (!/^[^/]+\/[^/]+$/.test(repository)) {
  throw new Error(`Invalid GitHub repository: ${repository}`)
}

const candidatesDirectory = resolve(candidatesDirectoryArg)
const outputDirectory = resolve(outputDirectoryArg)
const outputFromCandidates = relative(candidatesDirectory, outputDirectory)
const protectedDirectories = new Set([
  parse(outputDirectory).root,
  resolve(process.cwd()),
  resolve(homedir()),
  candidatesDirectory,
])
if (
  protectedDirectories.has(outputDirectory) ||
  (!outputFromCandidates.startsWith('..') && outputFromCandidates !== '')
) {
  throw new Error('OUTPUT must be a dedicated directory outside CANDIDATES')
}
const allowedSuffixes = [
  '.dmg',
  '.app.tar.gz',
  '.app.tar.gz.sig',
  '.deb',
  '.AppImage',
  '.AppImage.sig',
  '-setup.exe',
  '-setup.exe.sig',
  '.msi',
  '.msi.sig',
]

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

async function sha256(path) {
  const contents = await readFile(path)
  return createHash('sha256').update(contents).digest('hex')
}

function findBySuffix(files, suffix) {
  const matches = files.filter((path) => path.endsWith(suffix))
  if (matches.length !== 1) {
    throw new Error(
      `Expected exactly one ${suffix} candidate; found ${matches.length}`,
    )
  }
  return matches[0]
}

function releaseUrl(name) {
  return `https://github.com/${repository}/releases/download/${encodeURIComponent(releaseTag)}/${encodeURIComponent(name)}`
}

await rm(outputDirectory, { recursive: true, force: true })
await mkdir(outputDirectory, { recursive: true })

const candidateFiles = (await walk(candidatesDirectory)).filter((path) =>
  allowedSuffixes.some((suffix) => path.endsWith(suffix)),
)
const expectedArtifactCount = allowedSuffixes.length
if (candidateFiles.length !== expectedArtifactCount) {
  throw new Error(
    `Expected ${expectedArtifactCount} release candidates; found ${candidateFiles.length}`,
  )
}

const outputFiles = []
const names = new Set()
for (const sourcePath of candidateFiles.sort()) {
  const name = basename(sourcePath)
  if (names.has(name)) {
    throw new Error(`Duplicate release asset name: ${name}`)
  }
  names.add(name)

  const destinationPath = join(outputDirectory, name)
  await copyFile(sourcePath, destinationPath)
  if ((await stat(destinationPath)).size <= 0) {
    throw new Error(`Release asset is empty: ${name}`)
  }
  outputFiles.push(destinationPath)
}

const macUpdater = findBySuffix(outputFiles, '.app.tar.gz')
const macSignature = findBySuffix(outputFiles, '.app.tar.gz.sig')
findBySuffix(outputFiles, '.dmg')
findBySuffix(outputFiles, '.deb')
const linuxUpdater = findBySuffix(outputFiles, '.AppImage')
const linuxSignature = findBySuffix(outputFiles, '.AppImage.sig')
const windowsUpdater = findBySuffix(outputFiles, '-setup.exe')
const windowsSignature = findBySuffix(outputFiles, '-setup.exe.sig')
findBySuffix(outputFiles, '.msi')

const macPlatform = {
  signature: await readFile(macSignature, 'utf8'),
  url: releaseUrl(basename(macUpdater)),
}
const linuxPlatform = {
  signature: await readFile(linuxSignature, 'utf8'),
  url: releaseUrl(basename(linuxUpdater)),
}
const windowsPlatform = {
  signature: await readFile(windowsSignature, 'utf8'),
  url: releaseUrl(basename(windowsUpdater)),
}

for (const [label, platform] of Object.entries({
  macPlatform,
  linuxPlatform,
  windowsPlatform,
})) {
  if (!platform.signature.trim()) {
    throw new Error(`${label} updater signature is empty`)
  }
}

const latest = {
  version: expectedVersion,
  notes: `MeshX ${releaseTag} verified desktop release.`,
  pub_date: new Date().toISOString(),
  platforms: {
    'darwin-aarch64': macPlatform,
    'darwin-x86_64': macPlatform,
    'darwin-universal': macPlatform,
    'darwin-aarch64-app': macPlatform,
    'darwin-x86_64-app': macPlatform,
    'darwin-universal-app': macPlatform,
    'linux-x86_64': linuxPlatform,
    'linux-x86_64-appimage': linuxPlatform,
    'windows-x86_64': windowsPlatform,
    'windows-x86_64-nsis': windowsPlatform,
  },
}

const latestPath = join(outputDirectory, 'latest.json')
await writeFile(latestPath, `${JSON.stringify(latest, null, 2)}\n`)
outputFiles.push(latestPath)

const checksumLines = []
for (const path of outputFiles.sort((left, right) =>
  basename(left).localeCompare(basename(right)),
)) {
  checksumLines.push(`${await sha256(path)}  ${basename(path)}`)
}
await writeFile(
  join(outputDirectory, 'SHA256SUMS'),
  `${checksumLines.join('\n')}\n`,
)

process.stdout.write(
  `Assembled ${outputFiles.length} verified assets for ${releaseTag}.\n`,
)
for (const line of checksumLines) {
  process.stdout.write(`${line}\n`)
}
