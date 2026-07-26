import { clearLocalChatDatabase } from '../services/localChatDb'
import {
  clearCacheOwner,
  clearSession,
  readSession,
} from '../utils/storage'
import type { DesktopNode } from './nativeBridge'
import { nativeBridge } from './nativeBridge'
import { performNodeSwitch } from './nodeSwitch'
import {
  selectNode,
  selectedNode,
} from './nodeContext'

export async function activateDesktopNode(node: DesktopNode): Promise<boolean> {
  return performNodeSwitch({
    selectedNode,
    confirm: (message, options) => nativeBridge.confirm(message, options),
    nativeLogout: (origin, apiBasePath, token) => nativeBridge.nativeLogout(origin, apiBasePath, token),
    clearNodeSession: (origin) => nativeBridge.clearNodeSession(origin),
    clearLocalChatDatabase,
    clearSession,
    clearCacheOwner,
    selectNode,
    readToken: () => readSession()?.token,
  }, node)
}
