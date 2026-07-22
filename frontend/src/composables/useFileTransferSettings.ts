import { readonly, shallowRef } from 'vue'

const PREFER_DIRECT_FILE_TRANSFER_KEY = 'lanchat_prefer_direct_file_transfer'

function readPreferDirectFileTransfer(): boolean {
  try {
    return localStorage.getItem(PREFER_DIRECT_FILE_TRANSFER_KEY) === 'true'
  } catch {
    return false
  }
}

const preferDirectFileTransfer = shallowRef(readPreferDirectFileTransfer())

/**
 * Device-local file delivery preference. Node relay remains the default because
 * it is available across devices and does not depend on peer connectivity.
 */
export function useFileTransferSettings() {
  function setPreferDirectFileTransfer(enabled: boolean): void {
    preferDirectFileTransfer.value = enabled
    try {
      localStorage.setItem(PREFER_DIRECT_FILE_TRANSFER_KEY, String(enabled))
    } catch {
      // The in-memory value remains usable when browser storage is unavailable.
    }
  }

  return {
    preferDirectFileTransfer: readonly(preferDirectFileTransfer),
    setPreferDirectFileTransfer,
  }
}
