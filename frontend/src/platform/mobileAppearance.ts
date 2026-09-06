import { registerPlugin } from '@capacitor/core'
import { capacitorPlatform, isCapacitorRuntime } from './mobileRuntime'

const appearance = registerPlugin<{
  setTheme(options: { mode: 'light' | 'dark' }): Promise<void>
}>('MeshXAppearance')

/** The iOS container, status bar and keyboard must match the explicit app theme. */
export async function syncMobileAppearance(mode: 'light' | 'dark'): Promise<void> {
  if (!isCapacitorRuntime() || capacitorPlatform() !== 'ios') return
  try {
    await appearance.setTheme({ mode })
  } catch (error) {
    // Older installed native shells may not have this bridge yet.
    console.warn('MeshX 原生主题同步失败', error)
  }
}
