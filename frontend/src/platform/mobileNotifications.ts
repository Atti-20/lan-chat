import { LocalNotifications } from '@capacitor/local-notifications'
import { capacitorPlatform, isCapacitorRuntime } from './mobileRuntime'

export const MESSAGE_NOTIFICATION_CHANNEL_ID = 'meshx_messages'

export async function mobileNotificationPermission(): Promise<'granted' | 'denied' | 'prompt' | 'unavailable'> {
  if (!isCapacitorRuntime()) return 'unavailable'
  try {
    const { display } = await LocalNotifications.checkPermissions()
    return display === 'prompt-with-rationale' ? 'prompt' : display
  } catch {
    return 'unavailable'
  }
}

/**
 * Prepare notification delivery without forcing an iOS permission prompt at
 * launch. A real notification may opt in to requesting permission; Android
 * keeps its existing first-launch behavior.
 */
export async function initializeMobileNotification(
  options: { requestPermission?: boolean } = {},
): Promise<boolean> {
  try {
    return await initialize(options.requestPermission ?? capacitorPlatform() === 'android')
  } catch (error) {
    console.warn('MeshX 初始化通知失败', error)
    return false
  }
}

async function initialize(requestPermission: boolean): Promise<boolean> {
  if (!isCapacitorRuntime()) return false
  const platform = capacitorPlatform()
  if (platform !== 'android' && platform !== 'ios') return false
  let permission = await LocalNotifications.checkPermissions()
  if (requestPermission
    && (permission.display === 'prompt' || permission.display === 'prompt-with-rationale')) {
    permission = await LocalNotifications.requestPermissions()
  }
  if (permission.display !== 'granted') {
    console.info('MeshX 用户尚未授权通知权限:', permission.display)
    return false
  }
  // Android 8+ owns importance, sound and vibration through a channel. iOS
  // intentionally uses the system notification settings instead.
  if (platform === 'android') {
    await LocalNotifications.createChannel({
      id: MESSAGE_NOTIFICATION_CHANNEL_ID,
      name: '消息',
      description: 'MeshX 新消息通知',
      importance: 4,
      vibration: true,
    })
  }
  return true
}
