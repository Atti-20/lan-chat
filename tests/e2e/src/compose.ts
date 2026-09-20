import { execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)
const repositoryRoot = fileURLToPath(new URL('../../../', import.meta.url))

const composeArguments = [
  'compose',
  '-f',
  'compose.yaml',
  '-f',
  'compose.e2e.yaml',
]

export async function restartComposeService(service: string): Promise<void> {
  if (!/^[a-z0-9-]+$/.test(service)) {
    throw new Error(`Invalid Compose service name: ${service}`)
  }
  try {
    await execFileAsync('docker', [...composeArguments, 'restart', service], {
      cwd: repositoryRoot,
      env: process.env,
      timeout: 120_000,
      maxBuffer: 4 * 1024 * 1024,
    })
  } catch (cause) {
    const details = cause as Error & { stdout?: string; stderr?: string }
    throw new Error(
      `Failed to restart Compose service ${service}: ${details.message}`
      + `${details.stdout ? `\nstdout:\n${details.stdout}` : ''}`
      + `${details.stderr ? `\nstderr:\n${details.stderr}` : ''}`,
    )
  }
}

export async function waitForHealthyHttp(
  url: string,
  timeoutMs = 120_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let lastFailure = 'no response'
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(3_000) })
      if (response.ok) return
      lastFailure = `HTTP ${response.status}`
    } catch (cause) {
      lastFailure = cause instanceof Error ? cause.message : String(cause)
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }
  throw new Error(`Timed out waiting for ${url}: ${lastFailure}`)
}
