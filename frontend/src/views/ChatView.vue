<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import AdminWorkspace from '../components/admin/AdminWorkspace.vue'
import type { AdminModule } from '../components/admin/adminNavigation'
import BroadcastSidebar from '../components/broadcasts/BroadcastSidebar.vue'
import BroadcastWorkspace from '../components/broadcasts/BroadcastWorkspace.vue'
import AppRail from '../components/chat/AppRail.vue'
import ChatWorkspace from '../components/chat/ChatWorkspace.vue'
import ConversationSidebar from '../components/chat/ConversationSidebar.vue'
import GlobalModalHost from '../components/chat/GlobalModalHost.vue'
import NavigationCoordinator from '../components/chat/NavigationCoordinator.vue'
import type { NavigationBackAction, NavigationBackState } from '../components/chat/navigationState'
import WorkspaceWelcome from '../components/chat/WorkspaceWelcome.vue'
import { useAdmin } from '../composables/useAdmin'
import { useAuth } from '../composables/useAuth'
import { useChat, type ChatSection } from '../composables/useChat'
import { useBroadcasts } from '../composables/useBroadcasts'
import { useDiagnostics } from '../composables/useDiagnostics'
import { useTemporaryRooms } from '../composables/useTemporaryRooms'
import { useToast } from '../composables/useToast'
import { api, ApiError } from '../services/api'
import { navigateToApp } from '../platform/appNavigation'
import {
  claimNavigationForCurrentNode,
  pendingDesktopNavigation,
} from '../platform/desktopNavigation'
import {
  clearSelectedNode,
} from '../platform/nodeContext'
import {
  nativeBridge,
  type DesktopNavigationTarget,
} from '../platform/nativeBridge'
import type {
  AdminUser,
  BroadcastCreatePayload,
  BroadcastCompletePayload,
  ChatMessage,
  Conversation,
  EmergencyBroadcast,
  TemporaryRoom,
  TemporaryRoomCreatePayload,
  User,
} from '../types'

const auth = useAuth()
const chat = useChat()
const admin = useAdmin()
const toast = useToast()
const {
  friends,
  groups,
  requests,
  members,
  messages,
  conversations,
  totalUnreadCount,
  messageSearchResults,
  messageSearchLoading,
  messageSearchError,
  selected,
  section,
  query,
  loading,
  loadingMessages,
  typingLabel,
  fileTransferLabel,
  visibleConversations,
  connected,
  reconnecting,
  connectionState,
  reconnectAttempts,
  latencyMs,
  lastHeartbeatAt,
  lastSyncAt,
  pendingCount,
  failedCount,
} = chat
const temporaryRooms = useTemporaryRooms({
  onChanged: async () => { await chat.refreshLists() },
})
const {
  users: adminUsers,
  loading: adminLoading,
  loaded: adminLoaded,
  creating: adminCreating,
  createdUsername: adminCreatedUsername,
  busyUserId: adminBusyUserId,
} = admin
const searchOpen = shallowRef(false)
const groupOpen = shallowRef(false)
const roomCreateOpen = shallowRef(false)
const roomJoinOpen = shallowRef(false)
const broadcastCreateOpen = shallowRef(false)
const profileOpen = shallowRef(false)
const profileEditorOpen = shallowRef(false)
const contextOpen = shallowRef(false)
const devicesOpen = shallowRef(false)
const passwordOpen = shallowRef(false)
const passwordResetTarget = shallowRef<AdminUser | null>(null)
const desktopSettingsOpen = shallowRef(false)
const fileTransferSettingsOpen = shallowRef(false)
const adminModule = shallowRef<AdminModule | null>(null)
const groupSaving = shallowRef(false)
const profileSaving = shallowRef(false)
const uploading = shallowRef(false)
const broadcastConfirming = shallowRef(false)
const broadcastStatisticsLoading = shallowRef(false)
const replyTo = shallowRef<ChatMessage | null>(null)
// Keep this aligned with the 760px responsive media queries in the chat UI.
// Desktop and Web use the same width-based layout switch; runtime is irrelevant.
const MOBILE_BREAKPOINT = 760
const SIDEBAR_MIN_WIDTH = 280
const SIDEBAR_MAX_WIDTH = 520
const DESKTOP_CHROME_WIDTH = 112
const MIN_WORKSPACE_WIDTH = 360
const viewportWidth = shallowRef(window.innerWidth)
const sidebarWidth = shallowRef(clampSidebarWidth(320, viewportWidth.value))
const resizingSidebar = shallowRef(false)
let stopActiveSidebarResize: (() => void) | null = null

const user = computed<User>(() => auth.currentUser.value || {
  id: auth.session.value?.userId || 0,
  userId: auth.session.value?.userId,
  username: auth.session.value?.username || '',
  nickname: auth.session.value?.nickname || 'MeshX 用户',
  avatar: auth.session.value?.avatar,
})
const isAdminSection = computed(() => section.value === 'admin')
const isBroadcastSection = computed(() => section.value === 'broadcasts')
const isAdministrator = computed(() => user.value.username === 'admin')
const broadcasts = useBroadcasts({
  canViewAllStatistics: () => isAdministrator.value,
})
const canCreateBroadcast = computed(() => (
  isAdministrator.value || user.value.canSendBroadcast === 1
))
watch(canCreateBroadcast, (allowed) => {
  if (!allowed) broadcastCreateOpen.value = false
})
const mobile = computed(() => viewportWidth.value <= MOBILE_BREAKPOINT)
const sidebarMaxWidth = computed(() => sidebarMaximumForViewport(viewportWidth.value))
const diagnostics = useDiagnostics({
  connectionState,
  reconnectAttempts,
  latencyMs,
  lastHeartbeatAt,
  lastSyncAt,
  pendingCount,
  failedCount,
  isAdmin: isAdministrator,
})
const hasWorkspaceSelection = computed(() => {
  if (isAdminSection.value) return Boolean(adminModule.value)
  if (isBroadcastSection.value) return broadcasts.selectedId.value !== null
  return Boolean(selected.value)
})
const showSidebar = computed(() => !mobile.value || !hasWorkspaceSelection.value)
const showWorkspace = computed(() => !mobile.value || hasWorkspaceSelection.value)
const friendIds = computed(() => friends.value.map((friend) => friend.friendId))
const selectedTemporaryRoom = computed<TemporaryRoom | null>(() => selected.value?.kind === 'temporary'
  ? selected.value.source as TemporaryRoom
  : null)
const conversationWritable = computed(() => {
  const room = selectedTemporaryRoom.value
  if (!room) return true
  const expiresAt = Date.parse(room.expiresAt)
  return room.status === 'ACTIVE' && !Number.isNaN(expiresAt) && expiresAt > Date.now()
})
const conversationFileAllowed = computed(() => conversationWritable.value
  && (selectedTemporaryRoom.value?.allowFileUpload ?? true))
const conversationStatusLabel = computed(() => {
  const room = selectedTemporaryRoom.value
  if (!room || conversationWritable.value) return ''
  if (room.status === 'ARCHIVED') return '房间已归档，仅可查看历史消息'
  if (room.status === 'DESTROYED') return '房间已销毁'
  return '房间已到期或冻结，仅可查看历史消息'
})
const connectionCopy = computed(() => reconnecting.value
  ? '正在重连'
  : connectionState.value === 'SYNCING'
    ? '正在同步'
    : connected.value
      ? '实时在线'
      : '离线可用')
const profileConnectionSummary = computed(() => {
  const path = diagnostics.connectionPath.value === 'LOCAL'
    ? '本机节点'
    : diagnostics.connectionPath.value === 'LAN'
      ? '局域网节点'
      : '远程节点'
  const name = diagnostics.nodeInfo.value?.nodeName || 'MeshX 节点'
  const latency = latencyMs.value === null ? '' : ` · ${latencyMs.value} ms`
  return `${name} · ${path} · ${connectionCopy.value}${latency}`
})
const navigationBackState = computed<NavigationBackState>(() => ({
  emergencyAlert: Boolean(broadcasts.emergencyAlert.value),
  passwordReset: Boolean(passwordResetTarget.value),
  profileEditor: profileEditorOpen.value,
  contextPanel: contextOpen.value,
  searchPeople: searchOpen.value,
  createGroup: groupOpen.value,
  createRoom: roomCreateOpen.value,
  joinRoom: roomJoinOpen.value,
  createBroadcast: broadcastCreateOpen.value,
  devices: devicesOpen.value,
  password: passwordOpen.value,
  fileTransferSettings: fileTransferSettingsOpen.value,
  desktopSettings: desktopSettingsOpen.value,
  profile: profileOpen.value,
  adminModule: Boolean(adminModule.value),
  broadcast: broadcasts.selectedId.value !== null,
  conversation: Boolean(selected.value),
}))

onMounted(async () => {
  // 登录响应中的用户资料可能是旧快照；进入聊天前以 /user/info 的结果为准，
  // 确保导航栏、个人资料弹窗和消息头像使用同一份头像数据。
  const hydrated = await auth.hydrate()
  if (!hydrated) {
    navigateToApp('/', true)
    return
  }
  try {
    await Promise.all([chat.load(), broadcasts.load()])
    const pendingTarget = pendingDesktopNavigation()
    if (pendingTarget) await openDesktopNavigation(pendingTarget)
  } catch (cause) {
    handleError(cause, '载入聊天失败')
  }
})

onBeforeUnmount(() => {
  stopActiveSidebarResize?.()
})

function handleNavigationBack(action: NavigationBackAction): void {
  if (action === 'emergency-alert') broadcasts.closeEmergencyAlert()
  else if (action === 'password-reset') passwordResetTarget.value = null
  else if (action === 'profile-editor') { profileEditorOpen.value = false; profileOpen.value = true }
  else if (action === 'context-panel') contextOpen.value = false
  else if (action === 'search-people') searchOpen.value = false
  else if (action === 'create-group') groupOpen.value = false
  else if (action === 'create-room') roomCreateOpen.value = false
  else if (action === 'join-room') roomJoinOpen.value = false
  else if (action === 'create-broadcast') broadcastCreateOpen.value = false
  else if (action === 'devices') devicesOpen.value = false
  else if (action === 'password') passwordOpen.value = false
  else if (action === 'file-transfer-settings') fileTransferSettingsOpen.value = false
  else if (action === 'desktop-settings') desktopSettingsOpen.value = false
  else if (action === 'profile') profileOpen.value = false
  else if (action === 'admin-module') adminModule.value = null
  else if (action === 'broadcast') broadcasts.clearSelection()
  else if (action === 'conversation') selected.value = null
}

async function openDesktopNavigation(target: DesktopNavigationTarget): Promise<void> {
  if (loading.value || target.kind === 'node') return
  const claimedTarget = claimNavigationForCurrentNode(target)
  if (!claimedTarget) return
  target = claimedTarget
  if (target.kind === 'conversation') {
    changeSection('messages')
    const conversation = chat.conversations.value.find(
      (candidate) => candidate.conversationId === target.value,
    )
    if (!conversation) {
      toast.push('深链指定的会话当前不可用', 'warning')
      return
    }
    await selectConversation(conversation)
    return
  }
  if (target.kind === 'room') {
    await joinTemporaryRoom(target.value)
    return
  }
  const broadcastId = Number(target.value)
  if (Number.isSafeInteger(broadcastId) && broadcastId > 0) {
    await openEmergencyBroadcast(broadcastId)
  }
}

function handleViewportChange(width: number): void {
  viewportWidth.value = width
  sidebarWidth.value = clampSidebarWidth(sidebarWidth.value, viewportWidth.value)
  if (mobile.value) stopActiveSidebarResize?.()
}

function startSidebarResize(event: PointerEvent): void {
  if (mobile.value || event.button !== 0) return
  stopActiveSidebarResize?.()
  const target = event.currentTarget as HTMLButtonElement
  target.setPointerCapture(event.pointerId)
  const startX = event.clientX
  const startWidth = sidebarWidth.value
  const move = (next: PointerEvent) => {
    sidebarWidth.value = clampSidebarWidth(startWidth + next.clientX - startX, viewportWidth.value)
  }
  const stop = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    window.removeEventListener('blur', stop)
    resizingSidebar.value = false
    stopActiveSidebarResize = null
  }
  resizingSidebar.value = true
  stopActiveSidebarResize = stop
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop, { once: true })
  window.addEventListener('pointercancel', stop, { once: true })
  window.addEventListener('blur', stop, { once: true })
}

function resizeSidebarWithKeyboard(event: KeyboardEvent): void {
  const step = event.shiftKey ? 32 : 16
  let nextWidth = sidebarWidth.value
  if (event.key === 'ArrowLeft') nextWidth -= step
  else if (event.key === 'ArrowRight') nextWidth += step
  else if (event.key === 'Home') nextWidth = SIDEBAR_MIN_WIDTH
  else if (event.key === 'End') nextWidth = sidebarMaxWidth.value
  else return
  event.preventDefault()
  sidebarWidth.value = clampSidebarWidth(nextWidth, viewportWidth.value)
}

function resetSidebarWidth(): void {
  sidebarWidth.value = clampSidebarWidth(320, viewportWidth.value)
}

function clampSidebarWidth(width: number, currentViewportWidth: number): number {
  return Math.max(
    SIDEBAR_MIN_WIDTH,
    Math.min(sidebarMaximumForViewport(currentViewportWidth), Math.round(width)),
  )
}

function sidebarMaximumForViewport(currentViewportWidth: number): number {
  return Math.max(
    SIDEBAR_MIN_WIDTH,
    Math.min(SIDEBAR_MAX_WIDTH, currentViewportWidth - DESKTOP_CHROME_WIDTH - MIN_WORKSPACE_WIDTH),
  )
}

function changeSection(next: ChatSection): void {
  if (next === 'admin' && user.value.username !== 'admin') return
  section.value = next
  query.value = ''
  selected.value = null
  adminModule.value = null
  replyTo.value = null
  contextOpen.value = false
  if (next === 'admin' && !adminLoaded.value) void admin.loadUsers()
}

function selectAdminModule(next: AdminModule): void {
  adminModule.value = next
  if (next === 'accounts' && !adminLoaded.value) void admin.loadUsers()
  if (next === 'diagnostics') void diagnostics.refresh()
}

function handleWelcomeAction(activeSection: ChatSection): void {
  if (activeSection === 'groups') {
    groupOpen.value = true
    return
  }
  if (activeSection === 'messages' || activeSection === 'contacts') searchOpen.value = true
}

async function selectConversation(conversation: Conversation): Promise<void> {
  replyTo.value = null
  try {
    await chat.selectConversation(conversation)
  } catch (cause) {
    handleError(cause, '无法打开这段对话')
  }
}

async function sendMessage(content: string, burn: boolean): Promise<void> {
  if (await chat.sendText(content, { burn, replyToId: replyTo.value?.messageId })) {
    replyTo.value = null
  }
}

async function sendFile(file: File): Promise<void> {
  uploading.value = true
  try {
    await chat.sendFile(file)
    toast.push(file.type.startsWith('image/') ? '图片已发送' : '文件已发送', 'success', 1600)
  } catch (cause) {
    handleError(cause, '文件发送失败')
  } finally {
    uploading.value = false
  }
}

async function handleFriendRequest(requestId: number, accept: boolean): Promise<void> {
  try {
    await chat.handleRequest(requestId, accept)
  } catch (cause) {
    handleError(cause, '处理好友申请失败')
  }
}

async function sendFriendRequest(userId: number, message: string): Promise<void> {
  try {
    await chat.sendFriendRequest(userId, message)
    searchOpen.value = false
  } catch (cause) {
    handleError(cause, '好友申请发送失败')
  }
}

async function createGroup(name: string, memberIds: number[]): Promise<void> {
  groupSaving.value = true
  try {
    await chat.createGroup(name, memberIds)
    groupOpen.value = false
    changeSection('groups')
  } catch (cause) {
    handleError(cause, '创建群聊失败')
  } finally {
    groupSaving.value = false
  }
}

async function createTemporaryRoom(payload: TemporaryRoomCreatePayload): Promise<void> {
  try {
    const room = await temporaryRooms.create(payload)
    roomCreateOpen.value = false
    changeSection('groups')
    const conversation = chat.conversations.value.find(
      (item) => item.kind === 'temporary' && item.id === room.id,
    )
    if (conversation) await selectConversation(conversation)
    toast.push(`临时房间已创建，房间码：${room.roomCode || '仅所有者可见'}`, 'success', 4200)
  } catch (cause) {
    handleError(cause, '创建临时房间失败')
  }
}

async function joinTemporaryRoom(roomCode: string): Promise<void> {
  try {
    const room = await temporaryRooms.join(roomCode)
    roomJoinOpen.value = false
    changeSection('groups')
    const conversation = chat.conversations.value.find(
      (item) => item.kind === 'temporary' && item.id === room.id,
    )
    if (conversation) await selectConversation(conversation)
    toast.push(`已加入“${room.roomName}”`, 'success')
  } catch (cause) {
    handleError(cause, '加入临时房间失败')
  }
}

async function leaveTemporaryRoom(): Promise<void> {
  const room = selectedTemporaryRoom.value
  if (!room) return

  const confirmed = await nativeBridge.confirm(
      `确定退出临时房间“${room.roomName}”吗？\n\n`
      + '退出后将不再接收该房间的新消息，'
      + '需要房间码才能重新加入。',
      {
        title: '退出临时房间',
        kind: "warning",
        okLabel: '退出临时房间',
        cancelLabel: '停留临时房间'
      }
  )

  if (!confirmed) return

  try {
    await temporaryRooms.leave(room.id)

    // 清除该房间未读数和最新消息运行状态。
    chat.forgetConversation(room.conversationId)

    contextOpen.value = false
    selected.value = null

    await chat.refreshLists()

    toast.push('已退出临时房间，不再接收该房间消息', 'success')
  } catch (cause) {
    handleError(cause, '退出临时房间失败')
  }
}

async function selectBroadcast(broadcast: EmergencyBroadcast): Promise<void> {
  try {
    await broadcasts.selectBroadcast(broadcast.id)
  } catch (cause) {
    handleError(cause, '无法打开广播')
  }
}

async function createBroadcast(payload: BroadcastCreatePayload): Promise<void> {
  try {
    await broadcasts.createBroadcast(payload)
    broadcastCreateOpen.value = false
    toast.push('广播已发布并开始统计送达状态', 'success')
  } catch (cause) {
    handleError(cause, '发布广播失败')
  }
}

async function cancelBroadcast(): Promise<void> {
  const current = broadcasts.selected.value?.broadcast
  if (!current || !isAdministrator.value || current.status !== 'ACTIVE') return
  const confirmed = await nativeBridge.confirm(
      `确定撤销广播“${current.title}”吗？\n\n撤销后将停止提醒和确认，但历史记录及统计会保留。`,
      {
        title: '撤销广播',
        kind: 'warning',
        okLabel: '撤销广播',
        cancelLabel: '保留广播',
      },
  )
  if (!confirmed) return

  try {
    await broadcasts.cancelBroadcast(current.id)
    toast.push('广播已撤销，历史记录已保留', 'success')
  } catch (cause) {
    handleError(cause, '撤销广播失败')
  }
}

async function deleteBroadcast(): Promise<void> {
  const current = broadcasts.selected.value?.broadcast
  if (!current || !isAdministrator.value || current.status !== 'CANCELLED') return
  const confirmed = await nativeBridge.confirm(
    `确定永久删除广播“${current.title}”吗？\n\n删除后正文、接收记录和统计无法恢复。`,
    { title: '永久删除广播', kind: 'error', okLabel: '永久删除', cancelLabel: '取消' },
  )
  if (!confirmed) return
  try {
    await broadcasts.deleteBroadcast(current.id)
    toast.push('广播已永久删除', 'success')
  } catch (cause) {
    handleError(cause, '删除广播失败')
  }
}

async function completeBroadcast(payload: BroadcastCompletePayload): Promise<void> {
  broadcastConfirming.value = true
  try {
    await broadcasts.complete(payload)
    toast.push('任务已完成', 'success')
  } catch (cause) {
    handleError(cause, '完成广播失败')
  } finally {
    broadcastConfirming.value = false
  }
}

async function remindBroadcastRecipient(userId: number): Promise<void> {
  const current = broadcasts.selected.value?.broadcast
  if (!current) return
  try {
    await api.broadcasts.remind(current.id, userId)
    toast.push('已发送完成提醒', 'success')
  } catch (cause) {
    handleError(cause, '提醒发送失败')
  }
}

async function exportBroadcastExcel(): Promise<void> {
  const broadcastId = broadcasts.selected.value?.broadcast.id
  if (!broadcastId) return
  try {
    const { blob, fileName } = await api.broadcasts.exportExcel(broadcastId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (cause) {
    handleError(cause, '导出广播明细失败')
  }
}

function exportBroadcastImage(): void {
  const detail = broadcasts.selected.value
  if (!detail) return
  const { broadcast } = detail
  const canvas = document.createElement('canvas')
  canvas.width = 1200
  canvas.height = 720
  const context = canvas.getContext('2d')
  if (!context) return
  context.fillStyle = '#f8fafc'
  context.fillRect(0, 0, canvas.width, canvas.height)
  context.fillStyle = '#0f172a'
  context.font = '700 42px system-ui, sans-serif'
  context.fillText(broadcast.title, 70, 100)
  context.fillStyle = '#64748b'
  context.font = '24px system-ui, sans-serif'
  context.fillText(`广播 · ${broadcast.status === 'COMPLETED' ? '已完成' : broadcast.status === 'CANCELLED' ? '已撤销' : '进行中'}`, 70, 146)
  context.fillStyle = '#1e293b'
  context.font = '28px system-ui, sans-serif'
  const words = broadcast.content.match(/.{1,34}/g) ?? ['']
  words.slice(0, 12).forEach((line, index) => context.fillText(line, 70, 215 + index * 42))
  const stats = broadcasts.statistics.value
  if (stats) {
    context.fillStyle = '#eff6ff'
    context.fillRect(70, 585, 1060, 80)
    context.fillStyle = '#1d4ed8'
    context.font = '600 24px system-ui, sans-serif'
    context.fillText(`目标 ${stats.targetCount}   已送达 ${stats.deliveredCount}   已查看 ${stats.viewedCount}   已执行 ${stats.executedCount}`, 95, 635)
  }
  const anchor = document.createElement('a')
  anchor.href = canvas.toDataURL('image/png')
  anchor.download = `broadcast-${broadcast.id}.png`
  anchor.click()
}

async function confirmBroadcast(status: string, broadcastId?: number): Promise<void> {
  broadcastConfirming.value = true
  try {
    await broadcasts.confirm(status, broadcastId)
    toast.push('处理回执已提交', 'success')
  } catch (cause) {
    handleError(cause, '提交广播回执失败')
  } finally {
    broadcastConfirming.value = false
  }
}

async function refreshBroadcastStatistics(): Promise<void> {
  broadcastStatisticsLoading.value = true
  try {
    await broadcasts.refreshStatistics()
  } catch (cause) {
    handleError(cause, '刷新广播统计失败')
  } finally {
    broadcastStatisticsLoading.value = false
  }
}

async function openEmergencyBroadcast(broadcastId: number): Promise<void> {
  changeSection('broadcasts')
  const broadcast = broadcasts.broadcasts.value.find((item) => item.id === broadcastId)
  if (broadcast) await selectBroadcast(broadcast)
  else {
    try {
      await broadcasts.selectBroadcast(broadcastId)
    } catch (cause) {
      handleError(cause, '无法打开紧急广播')
    }
  }
}

async function saveProfile(payload: { nickname: string; avatar: string }): Promise<void> {
  profileSaving.value = true
  try {
    await auth.updateProfile(payload)
    toast.push('个人资料已更新', 'success')
    profileEditorOpen.value = false
    profileOpen.value = true
  } catch (cause) {
    handleError(cause, '保存个人资料失败')
  } finally {
    profileSaving.value = false
  }
}

async function logout(): Promise<void> {
  chat.disconnect()
  try {
    await auth.logout()
  } finally {
    navigateToApp('/')
  }
}

async function switchDesktopNode(): Promise<void> {
  const confirmed = await nativeBridge.confirm(
    '切换节点会退出当前设备会话并清理本地聊天缓存。是否继续？',
    {
      title: '切换 MeshX 节点',
      kind: 'warning',
      okLabel: '退出并切换',
      cancelLabel: '取消',
    },
  )
  if (!confirmed) return
  chat.disconnect()
  desktopSettingsOpen.value = false
  try {
    await auth.logout()
  } finally {
    clearSelectedNode()
    navigateToApp('/')
  }
}

async function togglePin(): Promise<void> {
  try {
    await chat.togglePin()
    toast.push(selected.value?.pinned ? '已取消置顶' : '对话已置顶', 'success')
  } catch (cause) {
    handleError(cause, '置顶操作失败')
  }
}

async function toggleMute(): Promise<void> {
  try {
    await chat.toggleMute()
    toast.push('提醒设置已更新', 'success')
  } catch (cause) {
    handleError(cause, '免打扰设置失败')
  }
}

async function deleteFriend(): Promise<void> {
  const confirmed = await nativeBridge.confirm(
    `确定删除好友“${selected.value?.name || ''}”吗？聊天记录会保留。`,
    {
      title: '删除好友',
      kind: 'warning',
      okLabel: '删除好友',
      cancelLabel: '取消',
    },
  )
  if (!confirmed) return
  try {
    await chat.deleteFriend()
    toast.push('好友已删除')
  } catch (cause) {
    handleError(cause, '删除好友失败')
  }
}

async function updateRemark(remark: string): Promise<void> {
  try {
    await chat.updateRemark(remark)
    toast.push(remark ? '备注已更新' : '备注已清除', 'success')
  } catch (cause) {
    handleError(cause, '修改备注失败')
  }
}

function handleError(cause: unknown, fallback: string): void {
  toast.push(cause instanceof ApiError || cause instanceof Error ? cause.message : fallback, 'danger')
}

async function clearBrowserCaches(): Promise<void> {
  try {
    const cleared = await diagnostics.clearBrowserCaches()
    toast.push(cleared ? `已清理 ${cleared} 个浏览器缓存` : '没有可清理的浏览器缓存', 'success')
  } catch (cause) {
    handleError(cause, '浏览器缓存清理失败')
  }
}

async function resetUserPassword(newPassword: string): Promise<void> {
  const target = passwordResetTarget.value
  if (!target) return
  const reset = await admin.resetUserPassword({ userId: target.id, newPassword })
  if (reset) passwordResetTarget.value = null
}
</script>

<template>
  <main class="chat-page">
    <NavigationCoordinator
      :back-state="navigationBackState"
      @navigate="openDesktopNavigation"
      @viewport-change="handleViewportChange"
      @back="handleNavigationBack"
    />

    <div v-if="loading && !auth.currentUser.value" class="boot-screen glass-surface">
      <span />
      <strong>正在整理你的对话空间</strong>
    </div>

    <div
      v-else
      class="chat-shell"
      :style="{ '--sidebar-width': `${sidebarWidth}px` }"
      :class="{
        'chat-shell--thread': mobile && hasWorkspaceSelection,
        'chat-shell--resizing': resizingSidebar,
      }"
    >
      <AppRail
        :section="section"
        :user="user"
        :request-count="requests.length"
        :message-count="totalUnreadCount"
        :broadcast-count="broadcasts.pendingCount.value"
        :connected="connected"
        @change="changeSection"
        @profile="profileOpen = true"
      />

      <AdminWorkspace
        v-if="isAdminSection"
        :module="adminModule"
        :users="adminUsers"
        :loading="adminLoading"
        :loaded="adminLoaded"
        :creating="adminCreating"
        :created-username="adminCreatedUsername"
        :busy-user-id="adminBusyUserId"
        :connection-state="connectionState"
        :connection-path="diagnostics.connectionPath.value"
        :node-address="diagnostics.nodeAddress.value"
        :web-socket-address="diagnostics.webSocketAddress.value"
        :node-info="diagnostics.nodeInfo.value"
        :admin-diagnostics="diagnostics.adminDiagnostics.value"
        :reconnect-attempts="reconnectAttempts"
        :latency-ms="latencyMs"
        :last-heartbeat-at="lastHeartbeatAt"
        :last-sync-at="lastSyncAt"
        :pending-count="pendingCount"
        :failed-count="failedCount"
        :browser-capabilities="diagnostics.browserCapabilities"
        :diagnostics-loading="diagnostics.loading.value"
        :diagnostics-error="diagnostics.error.value"
        :mobile="mobile"
        :show-sidebar="showSidebar"
        :show-workspace="showWorkspace"
        @select-module="selectAdminModule"
        @close-module="adminModule = null"
        @refresh-users="admin.loadUsers"
        @create-user="admin.createUser"
        @set-status="admin.setUserStatus"
        @set-mute="admin.setMutePeriod"
        @set-broadcast-permission="admin.setBroadcastPermission"
        @reset-password="passwordResetTarget = $event"
        @change-own-password="passwordOpen = true"
        @delete-user="admin.deleteUser"
        @refresh-diagnostics="diagnostics.refresh"
        @reconnect="chat.reconnect"
        @retry="chat.retryOutbox"
        @export-diagnostics="diagnostics.exportDiagnostics"
        @clear-cache="clearBrowserCaches"
      />

      <BroadcastSidebar
        v-else-if="isBroadcastSection"
        v-show="showSidebar"
        :broadcasts="broadcasts.broadcasts.value"
        :selected-id="broadcasts.selectedId.value ?? undefined"
        :loading="broadcasts.listLoading.value"
        :can-create="canCreateBroadcast"
        :pending-broadcast-ids="[...broadcasts.pendingIds.value]"
        @select="selectBroadcast"
        @create="broadcastCreateOpen = true"
      />

      <ConversationSidebar
        v-else
        v-show="showSidebar"
        v-model:query="query"
        :section="section"
        :conversations="visibleConversations"
        :all-conversations="conversations"
        :message-results="messageSearchResults"
        :message-search-loading="messageSearchLoading"
        :message-search-error="messageSearchError"
        :requests="requests"
        :selected-id="selected?.id"
        :selected-kind="selected?.kind"
        :loading="loading"
        @select="selectConversation"
        @handle-request="handleFriendRequest"
        @search-people="searchOpen = true"
        @create-group="groupOpen = true"
        @create-temporary-room="roomCreateOpen = true"
        @join-temporary-room="roomJoinOpen = true"
      />
      <button
        v-if="!mobile"
        class="sidebar-resizer"
        type="button"
        role="separator"
        aria-label="调整侧栏宽度"
        aria-orientation="vertical"
        :aria-valuemin="SIDEBAR_MIN_WIDTH"
        :aria-valuemax="sidebarMaxWidth"
        :aria-valuenow="sidebarWidth"
        title="拖动调整宽度，双击恢复默认"
        @pointerdown.prevent="startSidebarResize"
        @keydown="resizeSidebarWithKeyboard"
        @dblclick="resetSidebarWidth"
      />

      <section
        v-if="isBroadcastSection && broadcasts.selectedId.value !== null && showWorkspace"
        class="workspace workspace--broadcast"
      >
        <BroadcastWorkspace
          :detail="broadcasts.selected.value"
          :statistics="broadcasts.statistics.value"
          :loading="broadcasts.detailLoading.value"
          :confirming="broadcastConfirming"
          :statistics-loading="broadcastStatisticsLoading"
          :can-cancel="isAdministrator"
          :cancelling="broadcasts.cancelling.value"
          :can-delete="isAdministrator"
          :deleting="broadcasts.deleting.value"
          :mobile="mobile"
          :friends="friends"
          @confirm="confirmBroadcast"
          @refresh-stats="refreshBroadcastStatistics"
          @cancel="cancelBroadcast"
          @delete="deleteBroadcast"
          @complete="completeBroadcast"
          @remind="remindBroadcastRecipient"
          @export-excel="exportBroadcastExcel"
          @export-image="exportBroadcastImage"
          @back="broadcasts.clearSelection"
        />
      </section>

      <ChatWorkspace
        v-else-if="!isAdminSection && selected && showWorkspace"
        :conversation="selected"
        :messages="messages"
        :user="user"
        :members="members"
        :loading-messages="loadingMessages"
        :typing-label="typingLabel"
        :connected="connected"
        :uploading="uploading"
        :transfer-label="fileTransferLabel"
        :writable="conversationWritable"
        :file-allowed="conversationFileAllowed"
        :status-label="conversationStatusLabel"
        :mobile="mobile"
        :reply-to="replyTo"
        :context-open="contextOpen"
        @back="selected = null"
        @open-context="contextOpen = true"
        @recall="chat.recall"
        @burn="chat.burn"
        @reply="replyTo = $event"
        @retry="chat.retryMessage"
        @cancel-pending="chat.cancelPendingMessage"
        @send="sendMessage"
        @typing="chat.sendTyping"
        @file="sendFile"
        @cancel-reply="replyTo = null"
        @close-context="contextOpen = false"
        @toggle-pin="togglePin"
        @toggle-mute="toggleMute"
        @delete-friend="deleteFriend"
        @update-remark="updateRemark"
        @leave-room="leaveTemporaryRoom"
      />

      <WorkspaceWelcome
        v-else-if="!isAdminSection && showWorkspace"
        class="workspace workspace--welcome apple-content-surface"
        :section="section"
        @primary="handleWelcomeAction"
      />

    </div>

    <GlobalModalHost
      :search-open="searchOpen"
      :group-open="groupOpen"
      :room-create-open="roomCreateOpen"
      :room-join-open="roomJoinOpen"
      :broadcast-create-open="broadcastCreateOpen"
      :profile-open="profileOpen"
      :profile-editor-open="profileEditorOpen"
      :file-transfer-settings-open="fileTransferSettingsOpen"
      :desktop-settings-open="desktopSettingsOpen"
      :devices-open="devicesOpen"
      :password-open="passwordOpen"
      :password-reset-target="passwordResetTarget"
      :user="user"
      :friend-ids="friendIds"
      :friends="friends"
      :group-saving="groupSaving"
      :room-saving="temporaryRooms.saving.value"
      :administrator="isAdministrator"
      :broadcast-saving="broadcasts.saving.value"
      :emergency-broadcast="broadcasts.emergencyAlert.value?.broadcast"
      :emergency-confirmation-options="broadcasts.emergencyAlert.value?.confirmationOptions"
      :broadcast-confirming="broadcastConfirming"
      :profile-saving="profileSaving"
      :admin-busy-user-id="adminBusyUserId"
      :connection-summary="profileConnectionSummary"
      :desktop="nativeBridge.runtime() === 'tauri'"
      @close-search="searchOpen = false"
      @friend-request="sendFriendRequest"
      @close-group="groupOpen = false"
      @create-group="createGroup"
      @close-room-create="roomCreateOpen = false"
      @create-room="createTemporaryRoom"
      @close-room-join="roomJoinOpen = false"
      @join-room="joinTemporaryRoom"
      @close-broadcast-create="broadcastCreateOpen = false"
      @create-broadcast="createBroadcast"
      @open-broadcast="openEmergencyBroadcast"
      @confirm-broadcast="(broadcastId, status) => confirmBroadcast(status, broadcastId)"
      @dismiss-emergency="broadcasts.closeEmergencyAlert"
      @close-profile="profileOpen = false"
      @open-profile-editor="profileEditorOpen = true"
      @open-devices="devicesOpen = true"
      @open-password="passwordOpen = true"
      @open-file-transfer-settings="fileTransferSettingsOpen = true"
      @open-desktop-settings="desktopSettingsOpen = true"
      @logout="logout"
      @close-profile-editor="profileEditorOpen = false; profileOpen = true"
      @save-profile="saveProfile"
      @close-file-transfer-settings="fileTransferSettingsOpen = false"
      @close-desktop-settings="desktopSettingsOpen = false"
      @switch-node="switchDesktopNode"
      @close-devices="devicesOpen = false"
      @current-device-logged-out="logout"
      @close-password="passwordOpen = false"
      @password-changed="logout"
      @close-password-reset="passwordResetTarget = null"
      @reset-password="resetUserPassword"
    />
  </main>
</template>

<style scoped>
.chat-page {
  width: 100%;
  height: 100vh;
  min-height: 0;
  padding: 20px;
  overflow: hidden;
}
.chat-shell {
  --rail-width: 72px;
  position: relative;
  display: grid;
  width: 100%;
  height: calc(100vh - 40px);
  min-height: 0;
  max-height: calc(100vh - 40px);
  margin: 0 auto;
  grid-template-columns: var(--rail-width) var(--sidebar-width, 320px) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  align-items: stretch;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  border-radius: 28px;
  background: var(--surface-raise);
  box-shadow: 0 22px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}
.workspace {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: minmax(0, auto) minmax(0, 1fr) max-content;
  border-radius: 0;
  overflow: hidden;
  background: var(--surface);
}
.workspace--broadcast { grid-template-rows: minmax(0, 1fr); }
.workspace--welcome { grid-template-rows: minmax(0, 1fr); }
/* workspace-header/back-button 等标记已移入 ChatWorkspace/AdminWorkspace，
 * 其样式以子组件内的版本为唯一来源。 */
.workspace > :deep(.message-thread) { min-height: 0; overflow-y: auto; }
.workspace > :deep(.composer-wrap) { min-height: 0; }
.boot-screen { display: grid; width: min(420px, calc(100vw - 40px)); min-height: 180px; margin: calc(50dvh - 90px) auto 0; place-items: center; align-content: center; gap: 18px; border-radius: 22px; }
.boot-screen span { width: 30px; height: 30px; border: 2px solid rgba(10,132,255,.16); border-top-color: var(--blue); border-radius: 50%; animation: spin .8s linear infinite; }
.boot-screen strong { font-size: 13px; }
@keyframes spin { to { transform: rotate(360deg); } }

.sidebar-resizer {
  position: absolute;
  z-index: 8;
  top: 0;
  bottom: 0;
  left: calc(var(--rail-width) + var(--sidebar-width, 320px));
  width: 14px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: col-resize;
  touch-action: none;
  transform: translateX(-50%);
}
.sidebar-resizer::before,
.sidebar-resizer::after {
  position: absolute;
  left: 50%;
  content: "";
  transform: translateX(-50%);
  transition: background-color 150ms ease, border-color 150ms ease, box-shadow 150ms ease, opacity 150ms ease;
}
.sidebar-resizer::before {
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--separator);
}
.sidebar-resizer::after {
  top: calc(50% - 22px);
  width: 5px;
  height: 44px;
  border: 1px solid var(--separator-strong);
  border-radius: 999px;
  background: var(--surface-raise);
  box-shadow: 0 2px 8px var(--shadow-color);
  opacity: .72;
}
.sidebar-resizer:hover::before,
.sidebar-resizer:focus-visible::before,
.chat-shell--resizing .sidebar-resizer::before { background: color-mix(in srgb, var(--blue) 56%, var(--separator)); }
.sidebar-resizer:hover::after,
.sidebar-resizer:focus-visible::after,
.chat-shell--resizing .sidebar-resizer::after {
  border-color: color-mix(in srgb, var(--blue) 54%, var(--separator));
  box-shadow: 0 3px 12px color-mix(in srgb, var(--blue) 20%, transparent);
  opacity: 1;
}
.sidebar-resizer:focus-visible {
  outline: 3px solid rgba(0, 122, 255, 0.26);
  outline-offset: -3px;
}
.chat-shell--resizing,
.chat-shell--resizing * { cursor: col-resize !important; user-select: none; }

/* A tablet uses the desktop three-column layout, but its WebView can resize
 * while a thread is hydrated. Keep every structural column inside the single
 * constrained grid row so loading content cannot grow the rail or sidebar. */
@media (min-width: 761px) {
  .chat-shell > :deep(.app-rail),
  .chat-shell > :deep(.conversation-sidebar),
  .chat-shell > :deep(.broadcast-sidebar),
  .chat-shell > :deep(.admin-sidebar) {
    height: 100%;
    min-height: 0;
    max-height: 100%;
    overflow: hidden;
  }
}

@media (min-width: 761px) and (max-width: 1024px) {
  .chat-page { padding: 12px; }
  .chat-shell {
    --rail-width: 68px;
    height: calc(100vh - 24px);
    max-height: calc(100vh - 24px);
    border-radius: 22px;
  }
}

@media (max-width: 760px) {
  .chat-page { padding: 0; }
  .chat-shell {
    height: var(--app-viewport-height, 100vh);
    max-height: var(--app-viewport-height, 100vh);
    border: 0;
    border-radius: 0;
    box-shadow: none;
    grid-template-columns: minmax(0, 1fr);
  }
  /* 底部导航高度由各列表侧栏自行补偿（ConversationSidebar 等 88px），
   * 外层不再重复预留，避免约 88px 的空带。 */
  .chat-shell > :deep(.app-rail) { grid-column: 1; }
  .chat-shell--thread > :deep(.conversation-sidebar),
  .chat-shell--thread > :deep(.broadcast-sidebar),
  .chat-shell--thread > :deep(.admin-sidebar),
  .chat-shell--thread > :deep(.app-rail) { display: none !important; }
  .chat-shell--thread > .workspace { grid-column: 1; grid-row: 1; width: 100%; height: 100%; }
  .workspace { border-radius: 0; }
}

/* 横屏矮视口（手机横置）：压缩装饰性留白，保证线程与输入框可用。 */
@media (max-height: 480px) and (orientation: landscape) {
  .chat-page { padding: 0; }
  .chat-shell {
    height: var(--app-viewport-height, 100vh);
    max-height: var(--app-viewport-height, 100vh);
    border: 0;
    border-radius: 0;
    box-shadow: none;
  }
}

/* Keep the `vh` fallback as a separate feature query: the mobile optimizer
 * otherwise folds it into `dvh`, which older Android WebViews then discard. */
@supports (height: 100dvh) {
  .chat-page { height: 100dvh; }
  .chat-shell {
    height: calc(100dvh - 40px);
    max-height: calc(100dvh - 40px);
  }

  @media (max-width: 760px) {
    .chat-shell {
      height: var(--app-viewport-height, 100dvh);
      max-height: var(--app-viewport-height, 100dvh);
    }
  }
  @media (min-width: 761px) and (max-width: 1024px) {
    .chat-shell {
      height: calc(100dvh - 24px);
      max-height: calc(100dvh - 24px);
    }
  }
  /* 横屏矮视口需要压过上面的平板高度规则，保持全出血。 */
  @media (max-height: 480px) and (orientation: landscape) {
    .chat-shell {
      height: var(--app-viewport-height, 100dvh);
      max-height: var(--app-viewport-height, 100dvh);
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .boot-screen span { animation: none; }
  .header-profile,
  .sidebar-resizer::before,
  .sidebar-resizer::after { transition: none; }
}
</style>
