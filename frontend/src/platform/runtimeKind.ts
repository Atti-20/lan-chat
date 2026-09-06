export type RuntimeKind = 'web' | 'tauri' | 'capacitor'

/**
 * Resolves the native bridge from capabilities that the host has actually
 * injected. Build modes determine asset output only; they must never select
 * an IPC bridge because the same bundle is also useful in an ordinary browser
 * for preview and diagnosis.
 */
export function resolveRuntimeKind(signals: {
  tauri: boolean
  capacitor: boolean
}): RuntimeKind {
  if (signals.tauri) return 'tauri'
  if (signals.capacitor) return 'capacitor'
  return 'web'
}
