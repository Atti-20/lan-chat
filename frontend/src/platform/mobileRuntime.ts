interface CapacitorGlobal {
  isNativePlatform?: () => boolean
  getPlatform?: () => string
}

declare global {
  interface Window {
    Capacitor?: CapacitorGlobal
  }
}

export function isCapacitorRuntime(): boolean {
  return typeof window !== 'undefined'
    && window.Capacitor?.isNativePlatform?.() === true
}

export function capacitorPlatform(): 'android' | 'ios' | 'unknown' {
  const platform = window.Capacitor?.getPlatform?.()
  return platform === 'android' || platform === 'ios' ? platform : 'unknown'
}

export function isNativeNodeRuntime(): boolean {
  return typeof window !== 'undefined'
    && ('__TAURI_INTERNALS__' in window || isCapacitorRuntime())
}
