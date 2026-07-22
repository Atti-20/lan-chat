import { writeFile } from 'node:fs/promises'
import process from 'node:process'

const outputPath = process.argv[2]
if (!outputPath) {
  throw new Error('usage: write-tauri-updater-overlay.mjs OUTPUT_PATH')
}

const publicKey = process.env.TAURI_UPDATER_PUBLIC_KEY?.trim()
const endpoint = process.env.TAURI_UPDATER_ENDPOINT?.trim()
const releaseVersion = process.env.TAURI_RELEASE_VERSION?.trim()
if (!publicKey) {
  throw new Error('TAURI_UPDATER_PUBLIC_KEY is required')
}
if (!endpoint || !endpoint.startsWith('https://')) {
  throw new Error('TAURI_UPDATER_ENDPOINT must be an HTTPS URL')
}
if (!releaseVersion || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(releaseVersion)) {
  throw new Error('TAURI_RELEASE_VERSION must be a semantic version')
}

const overlay = {
  version: releaseVersion,
  bundle: {
    createUpdaterArtifacts: true,
  },
  plugins: {
    updater: {
      pubkey: publicKey,
      endpoints: [endpoint],
    },
  },
}

const windowsThumbprint =
  process.env.TAURI_WINDOWS_CERTIFICATE_THUMBPRINT?.trim()
if (windowsThumbprint) {
  overlay.bundle.windows = {
    certificateThumbprint: windowsThumbprint,
    timestampUrl: 'http://timestamp.digicert.com',
  }
}

await writeFile(outputPath, `${JSON.stringify(overlay, null, 2)}\n`, {
  mode: 0o600,
})
