<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import AdminConsole from '../components/admin/AdminConsole.vue'
import AdminPasswordResetModal from '../components/admin/AdminPasswordResetModal.vue'
import AdminSidebar from '../components/admin/AdminSidebar.vue'
import type { AdminModule } from '../components/admin/adminNavigation'
import RuntimeLogConsole from '../components/admin/logs/RuntimeLogConsole.vue'
import ConnectionDiagnosticsModal from '../components/diagnostics/ConnectionDiagnosticsModal.vue'
import AppRail from '../components/chat/AppRail.vue'
import ChangePasswordModal from '../components/chat/ChangePasswordModal.vue'
import ConnectionStatusBar from '../components/chat/ConnectionStatusBar.vue'
import ContextPanel from '../components/chat/ContextPanel.vue'
import ConversationSidebar from '../components/chat/ConversationSidebar.vue'
import CreateGroupModal from '../components/chat/CreateGroupModal.vue'
import DeviceManagerModal from '../components/chat/DeviceManagerModal.vue'
import MessageComposer from '../components/chat/MessageComposer.vue'
import MessageThread from '../components/chat/MessageThread.vue'
import ProfileModal from '../components/chat/ProfileModal.vue'
import SearchPeopleModal from '../components/chat/SearchPeopleModal.vue'
import UserAvatar from '../components/base/UserAvatar.vue'
import UiIcon from '../components/base/UiIcon.vue'
import WorkspaceWelcome from '../components/chat/WorkspaceWelcome.vue'
import { useAdmin } from '../composables/useAdmin'
import { useAuth } from '../composables/useAuth'
import { useChat, type ChatSection } from '../composables/useChat'
import { useDiagnostics } from '../composables/useDiagnostics'
import { useToast } from '../composables/useToast'
import { ApiError } from '../services/api'
import type { AdminUser, ChatMessage, Conversation, User } from '../types'

const auth = useAuth()
const chat = useChat()
const admin = useAdmin()
const toast = useToast()
const {
  friends,
  requests,
  members,
  messages,
  conversations,
  messageSearchResults,
  messageSearchLoading,
  messageSearchError,
  selected,
  section,
  query,
  loading,
  loadingMessages,
  typingLabel,
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
const profileOpen = shallowRef(false)
const contextOpen = shallowRef(false)
const devicesOpen = shallowRef(false)
const passwordOpen = shallowRef(false)
const passwordResetTarget = shallowRef<AdminUser | null>(null)
const diagnosticsOpen = shallowRef(false)
const adminModule = shallowRef<AdminModule | null>(null)
const groupSaving = shallowRef(false)
const profileSaving = shallowRef(false)
const uploading = shallowRef(false)
const replyTo = shallowRef<ChatMessage | null>(null)
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
  nickname: auth.session.value?.nickname || 'LanChat 用户',
  avatar: auth.session.value?.avatar,
})
const isAdminSection = computed(() => section.value === 'admin')
const isAdministrator = computed(() => user.value.username === 'admin')
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
const hasWorkspaceSelection = computed(() => isAdminSection.value
  ? Boolean(adminModule.value)
  : Boolean(selected.value))
const showSidebar = computed(() => !mobile.value || !hasWorkspaceSelection.value)
const showWorkspace = computed(() => !mobile.value || hasWorkspaceSelection.value)
const adminModuleTitles: Record<AdminModule, string> = {
  accounts: '账号管理',
  diagnostics: '连接诊断',
  logs: '运行日志',
}
const selectedAdminTitle = computed(() => adminModule.value ? adminModuleTitles[adminModule.value] : '管理')
const friendIds = computed(() => friends.value.map((friend) => friend.friendId))
const connectionCopy = computed(() => reconnecting.value
  ? '正在重连'
  : connectionState.value === 'SYNCING'
    ? '正在同步'
    : connected.value
      ? '实时在线'
      : '离线可用')

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  // 登录响应中的用户资料可能是旧快照；进入聊天前以 /user/info 的结果为准，
  // 确保导航栏、个人资料弹窗和消息头像使用同一份头像数据。
  const hydrated = await auth.hydrate()
  if (!hydrated) {
    window.location.replace('/')
    return
  }
  try {
    await chat.load()
  } catch (cause) {
    handleError(cause, '载入聊天失败')
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  stopActiveSidebarResize?.()
})

function handleResize(): void {
  viewportWidth.value = window.innerWidth
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

async function saveProfile(payload: { nickname: string; avatar: string }): Promise<void> {
  profileSaving.value = true
  try {
    await auth.updateProfile(payload)
    toast.push('个人资料已更新', 'success')
    profileOpen.value = false
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
    window.location.assign('/')
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
  if (!window.confirm(`确定删除好友"${selected.value?.name || ''}"吗？聊天记录会保留。`)) return
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
        :connected="connected"
        @change="changeSection"
        @profile="profileOpen = true"
      />

      <AdminSidebar
        v-if="isAdminSection"
        v-show="showSidebar"
        :selected="adminModule"
        :account-count="adminLoaded ? adminUsers.length : undefined"
        :connection-state="connectionState"
        @select="selectAdminModule"
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
        v-if="isAdminSection && adminModule && showWorkspace"
        class="workspace workspace--admin-module"
      >
        <header v-if="mobile" class="mobile-module-header">
          <button class="back-button" type="button" aria-label="返回管理模块" @click="adminModule = null">
            <UiIcon name="back" :size="21" />
          </button>
          <div>
            <span>节点控制台</span>
            <strong>{{ selectedAdminTitle }}</strong>
          </div>
        </header>
        <AdminConsole
          v-if="adminModule === 'accounts'"
          :users="adminUsers"
          :loading="adminLoading"
          :creating="adminCreating"
          :created-username="adminCreatedUsername"
          :busy-user-id="adminBusyUserId"
          :mobile="mobile"
          @refresh="admin.loadUsers"
          @create="admin.createUser"
          @status="admin.setUserStatus"
          @mute="admin.setMutePeriod"
          @reset-password="passwordResetTarget = $event"
          @change-own-password="passwordOpen = true"
          @delete="admin.deleteUser"
        />
        <ConnectionDiagnosticsModal
          v-else-if="adminModule === 'diagnostics'"
          :open="true"
          embedded
          :state="connectionState"
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
          :loading="diagnostics.loading.value"
          :error="diagnostics.error.value"
          @close="adminModule = null"
          @refresh="diagnostics.refresh"
          @reconnect="chat.reconnect"
          @retry="chat.retryOutbox"
          @export="diagnostics.exportDiagnostics"
          @clear-cache="clearBrowserCaches"
        />
        <RuntimeLogConsole v-else />
      </section>

      <section v-else-if="selected && showWorkspace" class="workspace">
        <header class="workspace-header">
          <button v-if="mobile" class="back-button" type="button" aria-label="返回会话列表" @click="selected = null">
            <UiIcon name="back" :size="21" />
          </button>
          <button class="header-profile" type="button" aria-label="查看详情" @click="contextOpen = true">
            <UserAvatar :name="selected.name" :avatar="selected.avatar" :size="42" />
            <div class="workspace-title">
              <strong>{{ selected.name }}</strong>
              <span><i :class="{ offline: !connected }" /> {{ connectionCopy }}</span>
            </div>
          </button>
        </header>

        <div class="connection-status-slot">
          <ConnectionStatusBar
            :state="connectionState"
            :pending-count="pendingCount"
            :failed-count="failedCount"
            :reconnect-attempts="reconnectAttempts"
            :latency-ms="latencyMs"
            :node-name="diagnostics.nodeInfo.value?.nodeName"
            :connection-path="diagnostics.connectionPath.value"
            :can-view-diagnostics="isAdministrator"
            @details="isAdministrator && (diagnosticsOpen = true)"
            @reconnect="chat.reconnect"
            @retry="chat.retryOutbox"
          />
        </div>

        <MessageThread
          :conversation="selected"
          :messages="messages"
          :user="user"
          :members="members"
          :loading="loadingMessages"
          :typing-label="typingLabel"
          @recall="chat.recall"
          @burn="chat.burn"
          @reply="replyTo = $event"
          @retry="chat.retryMessage"
          @cancel-pending="chat.cancelPendingMessage"
        />
        <MessageComposer
          :conversation="selected"
          :reply-to="replyTo"
          :connected="connected"
          :uploading="uploading"
          @send="sendMessage"
          @typing="chat.sendTyping"
          @file="sendFile"
          @cancel-reply="replyTo = null"
        />
      </section>

      <WorkspaceWelcome
        v-else-if="showWorkspace"
        class="workspace workspace--welcome"
        :section="section"
        @primary="handleWelcomeAction"
      />

      <ContextPanel
        :open="contextOpen"
        :conversation="selected!"
        :members="members"
        @close="contextOpen = false"
        @toggle-pin="togglePin"
        @toggle-mute="toggleMute"
        @delete-friend="deleteFriend"
        @update-remark="updateRemark"
      />
    </div>

    <SearchPeopleModal
      :open="searchOpen"
      :current-user-id="user.id"
      :friend-ids="friendIds"
      @close="searchOpen = false"
      @request="sendFriendRequest"
    />
    <CreateGroupModal
      :open="groupOpen"
      :friends="friends"
      :saving="groupSaving"
      @close="groupOpen = false"
      @create="createGroup"
    />
    <ProfileModal
      :open="profileOpen"
      :user="user"
      :saving="profileSaving"
      @close="profileOpen = false"
      @save="saveProfile"
      @logout="logout"
      @open-devices="profileOpen = false; devicesOpen = true"
      @open-password="profileOpen = false; passwordOpen = true"
    />
    <DeviceManagerModal
      :open="devicesOpen"
      @close="devicesOpen = false"
      @current-device-logged-out="logout"
    />
    <ChangePasswordModal
      :open="passwordOpen"
      @close="passwordOpen = false"
      @password-changed="logout"
    />
    <AdminPasswordResetModal
      :user="passwordResetTarget"
      :saving="Boolean(passwordResetTarget && adminBusyUserId === passwordResetTarget.id)"
      @close="passwordResetTarget = null"
      @reset="resetUserPassword"
    />
    <ConnectionDiagnosticsModal
      :open="diagnosticsOpen"
      :state="connectionState"
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
      :loading="diagnostics.loading.value"
      :error="diagnostics.error.value"
      @close="diagnosticsOpen = false"
      @refresh="diagnostics.refresh"
      @reconnect="chat.reconnect"
      @retry="chat.retryOutbox"
      @export="diagnostics.exportDiagnostics"
      @clear-cache="clearBrowserCaches"
    />
  </main>
</template>

<style scoped>
.chat-page { width: 100%; min-height: 100dvh; padding: 18px; }
.chat-shell { display: grid; width: 100%; height: calc(100dvh - 36px); margin: 0 auto; grid-template-columns: var(--rail-width, 72px) var(--sidebar-width, 320px) minmax(0, 1fr); gap: 10px; }
.workspace { display: grid; min-width: 0; min-height: 0; grid-template-rows: minmax(0, auto) minmax(0, auto) minmax(0, 1fr) max-content; border-radius: 18px; overflow: hidden; }
.connection-status-slot { min-width: 0; min-height: 0; }
.workspace-header { display: flex; padding: 14px 19px; align-items: center; gap: 12px; border-bottom: 1px solid rgba(255,255,255,.54); background: rgba(255,255,255,.16); }
.workspace-title { display: grid; min-width: 0; flex: 1; gap: 3px; }
.workspace-title strong { overflow: hidden; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.workspace-title span { color: #4d7ea7; font-size: 9px; font-weight: 650; }
.workspace-title i { display: inline-block; width: 7px; height: 7px; margin-right: 4px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 4px rgba(48,209,88,.1); }
.workspace-title i.offline { background: var(--ink-faint); box-shadow: none; }
.workspace-more,
.back-button { display: grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid var(--glass-border); border-radius: 13px; color: var(--ink-faint); background: var(--surface-glass); cursor: pointer; }
.workspace-more .ui-icon { width: 21px; }.back-button { display: none; }.back-button .ui-icon { width: 21px; }
.mobile-module-header { display: none; }
.boot-screen { display: grid; width: min(420px, calc(100vw - 40px)); min-height: 180px; margin: calc(50dvh - 90px) auto 0; place-items: center; align-content: center; gap: 18px; border-radius: 30px; }
.boot-screen span { width: 30px; height: 30px; border: 2px solid rgba(10,132,255,.16); border-top-color: var(--blue); border-radius: 50%; animation: spin .8s linear infinite; }.boot-screen strong { font-size: 13px; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 760px) {
  .chat-page { padding: 9px; }
  .chat-shell { height: calc(100dvh - 18px); grid-template-columns: minmax(0, 1fr); }
  .chat-shell > :deep(.app-rail) { grid-column: 1; }
  .chat-shell--thread > :deep(.conversation-sidebar),
  .chat-shell--thread > :deep(.admin-sidebar),
  .chat-shell--thread > :deep(.app-rail) { display: none !important; }
  .chat-shell--thread > .workspace { grid-column: 1; grid-row: 1; width: 100%; height: 100%; }
  .workspace { border-radius: 25px; }
  .back-button { display: grid; }
  .workspace-header { padding: 12px 13px; }
}

.chat-page { padding: 20px; }
.chat-shell {
  --rail-width: 72px;
  position: relative;
  width: 100%;
  height: calc(100dvh - 40px);
  grid-template-columns: var(--rail-width) var(--sidebar-width, 320px) minmax(0, 1fr);
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  border-radius: 28px;
  background: var(--surface-raise);
  box-shadow: 0 22px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}
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
.sidebar-resizer:focus-visible { outline: none; }
.chat-shell--resizing,
.chat-shell--resizing * { cursor: col-resize !important; user-select: none; }
.workspace { border-radius: 0; background: var(--surface); }
.workspace--welcome { grid-template-rows: minmax(0, 1fr); }
.workspace--admin-module { display: grid; min-width: 0; min-height: 0; grid-template-rows: minmax(0, 1fr); overflow: hidden; }
.connection-status-slot { overflow: visible; }
.workspace > :deep(.message-thread) { min-height: 0; overflow-y: auto; }
.workspace > :deep(.composer-wrap) { min-height: 0; }
.workspace-header {
  min-height: 70px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--separator);
  background: var(--surface-glass);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
}
.header-profile { display: flex; padding: 0; border: 0; align-items: center; gap: 12px; background: none; cursor: pointer; border-radius: 12px; transition: background-color 150ms ease; }
.header-profile:hover { background: var(--hover); }
.workspace-title strong { font-size: 15px; }
.workspace-title span { color: var(--ink-faint); font-size: 10px; font-weight: 500; }
.workspace-title i { width: 6px; height: 6px; box-shadow: none; }
.boot-screen { border-radius: 22px; }

@media (max-width: 760px) {
  .chat-page { padding: 0; }
  .chat-shell {
    height: 100dvh;
    border: 0;
    border-radius: 0;
    box-shadow: none;
    grid-template-columns: minmax(0, 1fr);
  }
  .workspace { border-radius: 0; }
  .workspace-header {
    padding: max(12px, env(safe-area-inset-top)) max(13px, env(safe-area-inset-right)) 12px max(13px, env(safe-area-inset-left));
  }
  .workspace--admin-module {
    grid-template-rows: auto minmax(0, 1fr);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .mobile-module-header {
    display: flex;
    min-height: 58px;
    padding: max(9px, env(safe-area-inset-top)) 12px 9px;
    align-items: center;
    gap: 10px;
    border-bottom: 1px solid var(--separator);
    background: var(--surface-raise);
  }
  .mobile-module-header .back-button { display: grid; }
  .mobile-module-header div { display: grid; gap: 2px; }
  .mobile-module-header span { color: var(--ink-faint); font-size: 9px; }
  .mobile-module-header strong { font-size: 13px; }
}
</style>
