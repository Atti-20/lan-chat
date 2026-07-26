<script setup lang="ts">
import { computed } from 'vue'
import type {
  AdminDiagnostics,
  AdminUser,
  ConnectionPath,
  ConnectionState,
  NodePublicInfo,
} from '../../types'
import UiIcon from '../base/UiIcon.vue'
import ConnectionDiagnosticsModal from '../diagnostics/ConnectionDiagnosticsModal.vue'
import WorkspaceWelcome from '../chat/WorkspaceWelcome.vue'
import RuntimeLogConsole from './logs/RuntimeLogConsole.vue'
import AdminConsole from './AdminConsole.vue'
import AdminSidebar from './AdminSidebar.vue'
import type { AdminModule } from './adminNavigation'

interface Props {
  module: AdminModule | null
  users: readonly AdminUser[]
  loading?: boolean
  loaded?: boolean
  creating?: boolean
  createdUsername?: string | null
  busyUserId?: number | null
  connectionState: ConnectionState
  connectionPath: ConnectionPath
  nodeAddress: string
  webSocketAddress: string
  nodeInfo: NodePublicInfo | null
  adminDiagnostics: AdminDiagnostics | null
  reconnectAttempts: number
  latencyMs: number | null
  lastHeartbeatAt: string | null
  lastSyncAt: string | null
  pendingCount: number
  failedCount: number
  browserCapabilities: Readonly<Record<string, boolean>>
  diagnosticsLoading?: boolean
  diagnosticsError?: string
  mobile?: boolean
  showSidebar?: boolean
  showWorkspace?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  loaded: false,
  creating: false,
  createdUsername: null,
  busyUserId: null,
  diagnosticsLoading: false,
  diagnosticsError: '',
  mobile: false,
  showSidebar: true,
  showWorkspace: true,
})

const emit = defineEmits<{
  selectModule: [module: AdminModule]
  closeModule: []
  refreshUsers: []
  createUser: [payload: { username: string; password: string; nickname: string }]
  setStatus: [payload: { userId: number; status: 0 | 1 }]
  setMute: [payload: { userId: number; muteStart: string; muteEnd: string }]
  setBroadcastPermission: [payload: { userId: number; enabled: boolean }]
  resetPassword: [user: AdminUser]
  changeOwnPassword: []
  deleteUser: [userId: number]
  refreshDiagnostics: []
  reconnect: []
  retry: []
  exportDiagnostics: []
  clearCache: []
}>()

const moduleTitles: Record<AdminModule, string> = {
  accounts: '账号管理',
  diagnostics: '连接诊断',
  logs: '运行日志',
}
const selectedTitle = computed(() => props.module ? moduleTitles[props.module] : '管理')
</script>

<template>
  <AdminSidebar
    v-show="showSidebar"
    :selected="module"
    :account-count="loaded ? users.length : undefined"
    :connection-state="connectionState"
    @select="emit('selectModule', $event)"
  />

  <section
    v-if="module && showWorkspace"
    class="workspace workspace--admin-module"
    aria-label="管理工作区"
  >
    <header v-if="mobile" class="mobile-module-header">
      <button class="back-button" type="button" aria-label="返回管理模块" @click="emit('closeModule')">
        <UiIcon name="back" :size="21" />
      </button>
      <div>
        <span>节点控制台</span>
        <strong>{{ selectedTitle }}</strong>
      </div>
    </header>

    <AdminConsole
      v-if="module === 'accounts'"
      :users="users"
      :loading="loading"
      :creating="creating"
      :created-username="createdUsername"
      :busy-user-id="busyUserId"
      :mobile="mobile"
      @refresh="emit('refreshUsers')"
      @create="emit('createUser', $event)"
      @status="emit('setStatus', $event)"
      @mute="emit('setMute', $event)"
      @broadcast-permission="emit('setBroadcastPermission', $event)"
      @reset-password="emit('resetPassword', $event)"
      @change-own-password="emit('changeOwnPassword')"
      @delete="emit('deleteUser', $event)"
    />
    <ConnectionDiagnosticsModal
      v-else-if="module === 'diagnostics'"
      :open="true"
      embedded
      :state="connectionState"
      :connection-path="connectionPath"
      :node-address="nodeAddress"
      :web-socket-address="webSocketAddress"
      :node-info="nodeInfo"
      :admin-diagnostics="adminDiagnostics"
      :reconnect-attempts="reconnectAttempts"
      :latency-ms="latencyMs"
      :last-heartbeat-at="lastHeartbeatAt"
      :last-sync-at="lastSyncAt"
      :pending-count="pendingCount"
      :failed-count="failedCount"
      :browser-capabilities="browserCapabilities"
      :loading="diagnosticsLoading"
      :error="diagnosticsError"
      @close="emit('closeModule')"
      @refresh="emit('refreshDiagnostics')"
      @reconnect="emit('reconnect')"
      @retry="emit('retry')"
      @export="emit('exportDiagnostics')"
      @clear-cache="emit('clearCache')"
    />
    <RuntimeLogConsole v-else />
  </section>

  <WorkspaceWelcome
    v-else-if="showWorkspace"
    class="workspace--welcome apple-content-surface"
    section="admin"
  />
</template>

<style scoped>
.workspace--admin-module {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
}
.workspace--welcome {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border-radius: 0;
  background: var(--surface);
}
.mobile-module-header { display: none; }
.back-button {
  display: grid;
  width: 40px;
  height: 40px;
  padding: 0;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--glass-border);
  border-radius: 13px;
  color: var(--ink-faint);
  background: var(--surface-glass);
  cursor: pointer;
}
.back-button:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 58%, transparent); outline-offset: 2px; }

@media (max-width: 760px) {
  .workspace--admin-module {
    grid-template-rows: auto minmax(0, 1fr);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .mobile-module-header {
    display: flex;
    min-height: 60px;
    padding: max(9px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) 9px max(12px, env(safe-area-inset-left));
    align-items: center;
    gap: 10px;
    border-bottom: 1px solid var(--separator);
    background: var(--surface-raise);
  }
  .mobile-module-header div { display: grid; gap: 2px; }
  .mobile-module-header span { color: var(--ink-faint); font-size: var(--font-micro); }
  .mobile-module-header strong { font-size: 14px; }
}
</style>
