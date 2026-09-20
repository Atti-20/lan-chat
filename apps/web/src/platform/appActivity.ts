import { isCapacitorRuntime } from './mobileRuntime'

let nativeActive: boolean | null = null

export function setNativeAppActive(active: boolean | null): void {
  nativeActive = active
}

export function isAppForeground(): boolean {
  // WKWebView visibility/focus can lag UIKit's background transition.
  if (isCapacitorRuntime() && nativeActive !== null) return nativeActive
  return document.visibilityState === 'visible' && document.hasFocus()
}
