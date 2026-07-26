import { App } from '@capacitor/app'
import { nativeBridge } from './nativeBridge'

/**
 * Capacitor does not guarantee the browser visibility event when Android
 * returns an existing WebView from the background. Re-emitting `online` on
 * activation lets the existing WebSocket composable refresh its token, reconnect
 * and request the normal server-side message sync.
 */
export async function installMobileLifecycle(): Promise<() => void> {
  if (nativeBridge.runtime() !== 'capacitor') return () => undefined

  const listener = await App.addListener('appStateChange', ({ isActive }) => {
    if (!isActive) return
    window.dispatchEvent(new Event('online'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  return () => listener.remove()
}
