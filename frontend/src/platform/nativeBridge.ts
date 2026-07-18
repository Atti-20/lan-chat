export type RuntimeKind = 'web' | 'tauri'

export interface RuntimeInfo {
  runtime: RuntimeKind
  platform: 'web' | 'macos' | 'windows' | 'linux' | 'unknown'
  version: string
}

export interface ConfirmOptions {
  title?: string
  kind?: 'info' | 'warning' | 'error'
  okLabel?: string
  cancelLabel?: string
}

export interface NativeBridge {
  runtime(): RuntimeKind
  runtimeInfo(): Promise<RuntimeInfo>
  confirm(message: string, options?: ConfirmOptions): Promise<boolean>
}

function isTauriRuntime(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

const webBridge: NativeBridge = {
  runtime: () => 'web',

  runtimeInfo: async () => ({
    runtime: 'web',
    platform: 'web',
    version: import.meta.env.VITE_APP_VERSION || 'web',
  }),

  confirm: async (message) => window.confirm(message),
}

const tauriBridge: NativeBridge = {
  runtime: () => 'tauri',

  runtimeInfo: async () => {
    const { invoke } = await import('@tauri-apps/api/core')
    return invoke<RuntimeInfo>('runtime_info')
  },

  confirm: async (message, options) => {
    const { confirm } = await import('@tauri-apps/plugin-dialog')
    return confirm(message, options)
  },
}

export const nativeBridge: NativeBridge = isTauriRuntime()
    ? tauriBridge
    : webBridge