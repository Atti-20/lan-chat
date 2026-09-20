export type NavigationBackAction =
  | 'emergency-alert'
  | 'password-reset'
  | 'profile-editor'
  | 'context-panel'
  | 'search-people'
  | 'create-group'
  | 'create-room'
  | 'join-room'
  | 'create-broadcast'
  | 'devices'
  | 'password'
  | 'file-transfer-settings'
  | 'desktop-settings'
  | 'profile'
  | 'admin-module'
  | 'broadcast'
  | 'conversation'
  | 'exit'

export interface NavigationBackState {
  emergencyAlert: boolean
  passwordReset: boolean
  profileEditor: boolean
  contextPanel: boolean
  searchPeople: boolean
  createGroup: boolean
  createRoom: boolean
  joinRoom: boolean
  createBroadcast: boolean
  devices: boolean
  password: boolean
  fileTransferSettings: boolean
  desktopSettings: boolean
  profile: boolean
  adminModule: boolean
  broadcast: boolean
  conversation: boolean
}

export function nextBackAction(state: NavigationBackState): NavigationBackAction {
  if (state.emergencyAlert) return 'emergency-alert'
  if (state.passwordReset) return 'password-reset'
  if (state.profileEditor) return 'profile-editor'
  if (state.contextPanel) return 'context-panel'
  if (state.searchPeople) return 'search-people'
  if (state.createGroup) return 'create-group'
  if (state.createRoom) return 'create-room'
  if (state.joinRoom) return 'join-room'
  if (state.createBroadcast) return 'create-broadcast'
  if (state.devices) return 'devices'
  if (state.password) return 'password'
  if (state.fileTransferSettings) return 'file-transfer-settings'
  if (state.desktopSettings) return 'desktop-settings'
  if (state.profile) return 'profile'
  if (state.adminModule) return 'admin-module'
  if (state.broadcast) return 'broadcast'
  if (state.conversation) return 'conversation'
  return 'exit'
}
