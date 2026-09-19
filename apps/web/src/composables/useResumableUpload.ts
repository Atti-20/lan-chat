import { computed, onBeforeUnmount, readonly, shallowRef } from 'vue'
import { api } from '../services/api'
import type { FileUpload, ResumableUploadSession } from '../types'
import { sha256Blob } from '../utils/sha256'

const FALLBACK_CHUNK_SIZE = 5 * 1024 * 1024
const MAX_PARALLEL_PARTS = 3
const MAX_PART_ATTEMPTS = 3

type UploadPhase = 'IDLE' | 'HASHING' | 'UPLOADING' | 'COMPLETING'

export function useResumableUpload() {
  const phase = shallowRef<UploadPhase>('IDLE')
  const progress = shallowRef(0)
  const activeUploadId = shallowRef<string | null>(null)
  let abortController: AbortController | null = null

  const active = computed(() => phase.value !== 'IDLE')

  onBeforeUnmount(() => abortController?.abort())

  async function upload(file: File, conversationId: string): Promise<FileUpload> {
    if (active.value) throw new Error('已有文件正在上传')
    if (!conversationId) throw new Error('上传会话无效')
    if (file.size <= 0) throw new Error('文件不能为空')

    const controller = new AbortController()
    abortController = controller
    phase.value = 'HASHING'
    progress.value = 0

    try {
      const fileHash = await sha256Blob(file, controller.signal)
      const clientUploadId = await stableUploadId(
        file,
        conversationId,
        fileHash,
        controller.signal,
      )
      const session = await api.files.createUpload({
        clientUploadId,
        conversationId,
        fileName: file.name,
        fileSize: file.size,
        fileType: file.type || 'application/octet-stream',
        fileHash,
      }, controller.signal)
      activeUploadId.value = session.uploadId
      if (session.completedFile) {
        progress.value = 100
        return session.completedFile
      }

      await uploadMissingParts(file, session, controller)
      phase.value = 'COMPLETING'
      const completed = await api.files.completeUpload(session.uploadId, controller.signal)
      progress.value = 100
      return completed
    } finally {
      abortController = null
      activeUploadId.value = null
      phase.value = 'IDLE'
    }
  }

  async function uploadMissingParts(
    file: File,
    initial: ResumableUploadSession,
    controller: AbortController,
  ): Promise<void> {
    const signal = controller.signal
    const current = initial.uploadedParts?.length
      ? initial
      : await api.files.uploadStatus(initial.uploadId, signal)
    const chunkSize = positiveInteger(current.chunkSize) || FALLBACK_CHUNK_SIZE
    const totalParts = positiveInteger(current.totalParts)
      || Math.ceil(file.size / chunkSize)
    const completed = new Set(current.uploadedParts || [])
    progress.value = calculateProgress(file.size, chunkSize, totalParts, completed)
    phase.value = 'UPLOADING'

    // The HTTP contract uses one-based part numbers (1..totalParts).
    const missing = Array.from({ length: totalParts }, (_, index) => index + 1)
      .filter((partNumber) => !completed.has(partNumber))
    let cursor = 0

    async function worker(): Promise<void> {
      while (cursor < missing.length) {
        const partNumber = missing[cursor++]
        if (partNumber === undefined) return
        if (signal.aborted) throw new DOMException('上传已取消', 'AbortError')
        const start = (partNumber - 1) * chunkSize
        const chunk = file.slice(start, Math.min(file.size, start + chunkSize))
        const partHash = await sha256Blob(chunk, signal)
        await retryPart(
          () => api.files.uploadPart(
            current.uploadId,
            partNumber,
            partHash,
            chunk,
            signal,
          ),
          signal,
        )
        completed.add(partNumber)
        progress.value = calculateProgress(file.size, chunkSize, totalParts, completed)
      }
    }

    let firstFailure: unknown
    const workers = Array.from(
      { length: Math.min(MAX_PARALLEL_PARTS, Math.max(1, missing.length)) },
      async () => {
        try {
          await worker()
        } catch (cause) {
          if (firstFailure === undefined) firstFailure = cause
          controller.abort()
          throw cause
        }
      },
    )
    await Promise.allSettled(workers)
    if (firstFailure !== undefined) throw firstFailure
  }

  async function cancel(): Promise<void> {
    abortController?.abort()
    const uploadId = activeUploadId.value
    if (uploadId) await api.files.cancelUpload(uploadId).catch(() => undefined)
  }

  return {
    active,
    phase: readonly(phase),
    progress: readonly(progress),
    activeUploadId: readonly(activeUploadId),
    upload,
    cancel,
  }
}

async function retryPart(
  action: () => Promise<unknown>,
  signal: AbortSignal,
): Promise<void> {
  let lastError: unknown
  for (let attempt = 1; attempt <= MAX_PART_ATTEMPTS; attempt += 1) {
    if (signal.aborted) throw new DOMException('上传已取消', 'AbortError')
    await waitUntilOnline(signal)
    try {
      await action()
      return
    } catch (cause) {
      lastError = cause
      if (attempt === MAX_PART_ATTEMPTS || signal.aborted) break
      await delay(attempt * 350, signal)
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分片上传失败')
}

function waitUntilOnline(signal: AbortSignal): Promise<void> {
  if (navigator.onLine) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      window.removeEventListener('online', handleOnline)
      signal.removeEventListener('abort', handleAbort)
    }
    const handleOnline = () => {
      cleanup()
      resolve()
    }
    const handleAbort = () => {
      cleanup()
      reject(new DOMException('上传已取消', 'AbortError'))
    }
    window.addEventListener('online', handleOnline, { once: true })
    signal.addEventListener('abort', handleAbort, { once: true })
  })
}

function calculateProgress(
  fileSize: number,
  chunkSize: number,
  totalParts: number,
  completed: Set<number>,
): number {
  let uploadedBytes = 0
  completed.forEach((partNumber) => {
    if (partNumber < 1 || partNumber > totalParts) return
    uploadedBytes += Math.min(chunkSize, fileSize - (partNumber - 1) * chunkSize)
  })
  // 99% is reserved for the server-side merge and final security validation.
  return Math.min(99, Math.round(uploadedBytes / fileSize * 99))
}

async function stableUploadId(
  file: File,
  conversationId: string,
  fileHash: string,
  signal: AbortSignal,
): Promise<string> {
  const seed = new Blob([
    conversationId,
    '\u0000',
    file.name,
    '\u0000',
    String(file.size),
    '\u0000',
    String(file.lastModified),
    '\u0000',
    fileHash,
  ])
  return `web_${await sha256Blob(seed, signal)}`
}

function positiveInteger(value: number): number {
  return Number.isSafeInteger(value) && value > 0 ? value : 0
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => signal.removeEventListener('abort', handleAbort)
    const handleAbort = () => {
      cleanup()
      window.clearTimeout(timer)
      reject(new DOMException('上传已取消', 'AbortError'))
    }
    const timer = window.setTimeout(() => {
      cleanup()
      resolve()
    }, milliseconds)
    signal.addEventListener('abort', handleAbort, { once: true })
  })
}
