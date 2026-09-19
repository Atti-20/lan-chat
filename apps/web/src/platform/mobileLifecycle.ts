import { App } from '@capacitor/app'
import { nativeBridge } from './nativeBridge'
import { setNativeAppActive } from './appActivity'

/**
 * Capacitor does not guarantee the browser visibility event when Android
 * returns an existing WebView from the background. Re-emitting `online` on
 * activation lets the existing WebSocket composable refresh its token, reconnect
 * and request the normal server-side message sync.
 */
export async function installMobileLifecycle(): Promise<() => void> {
  if (nativeBridge.runtime() !== 'capacitor') return () => undefined

  let stateChanged = false
  const listener = await App.addListener('appStateChange', ({ isActive }) => {
    stateChanged = true
    setNativeAppActive(isActive)
    if (isActive) window.dispatchEvent(new Event('online'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  try {
    const { isActive } = await App.getState()
    if (!stateChanged) setNativeAppActive(isActive)
  } catch {
    // Keep the document-state fallback if the native state is unavailable.
  }
  return () => {
    setNativeAppActive(null)
    void listener.remove()
  }
}
