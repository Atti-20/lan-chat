<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import AdminConsoleModal from '../components/admin/AdminConsoleModal.vue'
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
import { useAdmin } from '../composables/useAdmin'
import { useAuth } from '../composables/useAuth'
import { useChat, type ChatSection } from '../composables/useChat'
import { useToast } from '../composables/useToast'
import { ApiError } from '../services/api'
import type { ChatMessage, Conversation, User } from '../types'

const auth = useAuth()
const chat = useChat()
const admin = useAdmin()
const toast = useToast()
const {
  friends,
  requests,
  members,
  messages,
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
  pendingCount,
  failedCount,
} = chat
const {
  users: adminUsers,
  loading: adminLoading,
  busyUserId: adminBusyUserId,
} = admin
const searchOpen = shallowRef(false)
const groupOpen = shallowRef(false)
const profileOpen = shallowRef(false)
const adminOpen = shallowRef(false)
const contextOpen = shallowRef(false)
const devicesOpen = shallowRef(false)
const passwordOpen = shallowRef(false)
const groupSaving = shallowRef(false)
const profileSaving = shallowRef(false)
const uploading = shallowRef(false)
const replyTo = shallowRef<ChatMessage | null>(null)
const mobile = shallowRef(window.innerWidth <= 760)

const user = computed<User>(() => auth.currentUser.value || {
  id: auth.session.value?.userId || 0,
  userId: auth.session.value?.userId,
  username: auth.session.value?.username || '',
  nickname: auth.session.value?.nickname || 'LanChat 用户',
  avatar: auth.session.value?.avatar,
})
const showSidebar = computed(() => !mobile.value || !selected.value)
const showWorkspace = computed(() => !mobile.value || Boolean(selected.value))
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
  const hydrated = auth.currentUser.value || await auth.hydrate()
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

onBeforeUnmount(() => window.removeEventListener('resize', handleResize))

function handleResize(): void {
  mobile.value = window.innerWidth <= 760
}

function changeSection(next: ChatSection): void {
  section.value = next
  query.value = ''
  if (mobile.value) selected.value = null
}

async function openAdminConsole(): Promise<void> {
  if (user.value.username !== 'admin') return
  adminOpen.value = true
  await admin.loadUsers()
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
    section.value = 'groups'
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
  await auth.logout()
  window.location.assign('/')
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
      :class="{
        'chat-shell--thread': mobile && selected,
      }"
    >
      <AppRail
        :section="section"
        :user="user"
        :request-count="requests.length"
        :connected="connected"
        @change="changeSection"
        @admin="openAdminConsole"
        @profile="profileOpen = true"
      />

      <ConversationSidebar
        v-show="showSidebar"
        v-model:query="query"
        :section="section"
        :conversations="visibleConversations"
        :requests="requests"
        :selected-id="selected?.id"
        :selected-kind="selected?.kind"
        :loading="loading"
        @select="selectConversation"
        @handle-request="handleFriendRequest"
        @search-people="searchOpen = true"
        @create-group="groupOpen = true"
      />

      <section v-if="selected && showWorkspace" class="workspace">
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

        <ConnectionStatusBar
          v-if="connectionState !== 'ONLINE' || pendingCount > 0 || failedCount > 0"
          :state="connectionState"
          :pending-count="pendingCount"
          :failed-count="failedCount"
          :reconnect-attempts="reconnectAttempts"
          :latency-ms="latencyMs"
          @reconnect="chat.reconnect"
          @retry="chat.retryOutbox"
        />

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

      <section v-else-if="showWorkspace" class="workspace workspace--empty">
        <div class="empty-prism" aria-hidden="true">
          <UiIcon name="brand" :size="42" />
        </div>
        <h2>选择一段对话</h2>
        <p>左侧是最近的消息、好友与群组。<br />选中后，就能从上次停下的地方继续。</p>
        <button class="secondary-button" type="button" @click="searchOpen = true">开始新对话</button>
      </section>

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
    />
    <ChangePasswordModal
      :open="passwordOpen"
      @close="passwordOpen = false"
      @password-changed="logout"
    />
    <AdminConsoleModal
      :open="adminOpen"
      :users="adminUsers"
      :loading="adminLoading"
      :busy-user-id="adminBusyUserId"
      @close="adminOpen = false"
      @refresh="admin.loadUsers"
      @status="admin.setUserStatus"
      @mute="admin.setMutePeriod"
      @delete="admin.deleteUser"
    />
  </main>
</template>

<style scoped>
.chat-page { width: 100%; min-height: 100dvh; padding: 18px; }
.chat-shell { display: grid; width: 100%; height: calc(100dvh - 36px); margin: 0 auto; grid-template-columns: 78px 330px minmax(0, 1fr); gap: 10px; }
.workspace { display: grid; min-width: 0; min-height: 0; grid-template-rows: 76px auto minmax(0, 1fr) auto; border-radius: 18px; overflow: hidden; }
.workspace-header { display: flex; padding: 14px 19px; align-items: center; gap: 12px; border-bottom: 1px solid rgba(255,255,255,.54); background: rgba(255,255,255,.16); }
.workspace-title { display: grid; min-width: 0; flex: 1; gap: 3px; }
.workspace-title strong { overflow: hidden; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.workspace-title span { color: #4d7ea7; font-size: 9px; font-weight: 650; }
.workspace-title i { display: inline-block; width: 7px; height: 7px; margin-right: 4px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 4px rgba(48,209,88,.1); }
.workspace-title i.offline { background: var(--ink-faint); box-shadow: none; }
.workspace-more,
.back-button { display: grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid var(--glass-border); border-radius: 13px; color: var(--ink-faint); background: var(--surface-glass); cursor: pointer; }
.workspace-more .ui-icon { width: 21px; }.back-button { display: none; }.back-button .ui-icon { width: 21px; }
.workspace--empty { place-items: center; align-content: center; text-align: center; grid-template-rows: auto; }
.empty-prism { position: relative; display: grid; width: 150px; height: 150px; margin-bottom: 22px; place-items: center; border: 1px solid rgba(255,255,255,.74); border-radius: 46% 54% 52% 48%; color: var(--blue); background: linear-gradient(145deg, rgba(255,255,255,.66), rgba(205,233,255,.35)); box-shadow: inset 0 1px 0 #fff, 0 24px 46px rgba(52,91,132,.14); transform: rotate(-4deg); }
.empty-prism::before { position: absolute; inset: 18px; border: 1px solid rgba(118,103,245,.14); border-radius: 55% 45% 49% 51%; content: ""; transform: rotate(12deg); }
.empty-prism .ui-icon { z-index: 1; width: 72px; }
.empty-prism span { position: absolute; z-index: 2; width: 11px; height: 11px; border: 3px solid white; border-radius: 50%; background: var(--cyan); box-shadow: 0 5px 12px rgba(10,132,255,.22); }
.empty-prism span:nth-child(1) { top: 12px; right: 22px; }.empty-prism span:nth-child(2) { bottom: 19px; left: 7px; background: var(--green); }.empty-prism span:nth-child(3) { right: 5px; bottom: 39px; background: var(--violet); }
.workspace--empty h2 { margin: 0; font-size: 23px; letter-spacing: -.04em; }.workspace--empty p { margin: 10px 0 20px; color: var(--ink-soft); font-size: 12px; line-height: 1.7; }
.boot-screen { display: grid; width: min(420px, calc(100vw - 40px)); min-height: 180px; margin: calc(50dvh - 90px) auto 0; place-items: center; align-content: center; gap: 18px; border-radius: 30px; }
.boot-screen span { width: 30px; height: 30px; border: 2px solid rgba(10,132,255,.16); border-top-color: var(--blue); border-radius: 50%; animation: spin .8s linear infinite; }.boot-screen strong { font-size: 13px; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 1180px) {
  .chat-shell { grid-template-columns: 78px 300px minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .chat-page { padding: 9px; }
  .chat-shell { height: calc(100dvh - 18px); grid-template-columns: minmax(0, 1fr); }
  .chat-shell > :deep(.app-rail) { grid-column: 1; }
  .chat-shell--thread > :deep(.conversation-sidebar) { display: none !important; }
  .workspace { border-radius: 25px; }
  .back-button { display: grid; }
  .workspace-header { padding: 12px 13px; }
}

.chat-page { padding: 20px; }
.chat-shell {
  width: 100%;
  height: calc(100dvh - 40px);
  grid-template-columns: 72px 320px minmax(0, 1fr);
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  border-radius: 28px;
  background: var(--surface-raise);
  box-shadow: 0 22px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}
.workspace { border-radius: 0; background: var(--surface); }
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
.workspace--empty { background: var(--surface); }
.empty-prism {
  width: 84px;
  height: 84px;
  margin-bottom: 18px;
  border: 0;
  border-radius: 26px;
  color: var(--blue);
  background: var(--fill);
  box-shadow: none;
  transform: none;
}
.empty-prism::before { display: none; }
.empty-prism .ui-icon { width: 42px; }
.workspace--empty h2 { font-size: 20px; }
.workspace--empty p { color: var(--ink-soft); font-size: 12px; }
.boot-screen { border-radius: 22px; }

@media (max-width: 1180px) {
  .chat-shell { grid-template-columns: 72px 294px minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .chat-page { padding: 0; }
  .chat-shell {
    height: 100dvh;
    border: 0;
    border-radius: 0;
    box-shadow: none;
    grid-template-columns: minmax(0, 1fr);
  }
  .chat-shell--thread > :deep(.app-rail) { display: none; }
  .workspace { border-radius: 0; }
  .workspace-header { padding-top: max(12px, env(safe-area-inset-top)); }
}
</style>
