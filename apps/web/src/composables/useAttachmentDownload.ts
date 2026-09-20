import { computed, onScopeDispose, readonly, shallowRef } from 'vue'
import {
  nativeBridge,
  type NativeFileSaveResult,
} from '../platform/nativeBridge'

export interface AttachmentDownloadInput {
  url: string
  name: string
  mimeType?: string
  expectedBytes?: number
  sha256?: string
}

export function useAttachmentDownload() {
  const saving = shallowRef(false)
  const writtenBytes = shallowRef(0)
  const totalBytes = shallowRef<number | null>(null)
  let controller: AbortController | null = null

  const percent = computed(() => totalBytes.value && totalBytes.value > 0
    ? Math.min(100, Math.round((writtenBytes.value / totalBytes.value) * 100))
    : null)

  async function save(input: AttachmentDownloadInput): Promise<NativeFileSaveResult | null> {
    if (saving.value) return null
    controller = new AbortController()
    saving.value = true
    writtenBytes.value = 0
    totalBytes.value = input.expectedBytes ?? null
    try {
      return await nativeBridge.saveFile(input.url, input.name, input.mimeType, {
        expectedBytes: input.expectedBytes,
        sha256: input.sha256,
        signal: controller.signal,
        onProgress: (progress) => {
          writtenBytes.value = progress.writtenBytes
          totalBytes.value = progress.totalBytes ?? totalBytes.value
        },
      })
    } finally {
      saving.value = false
      controller = null
    }
  }

  function cancel(): void {
    controller?.abort()
  }

  onScopeDispose(cancel)

  return {
    saving: readonly(saving),
    writtenBytes: readonly(writtenBytes),
    totalBytes: readonly(totalBytes),
    percent,
    save,
    cancel,
  }
}
