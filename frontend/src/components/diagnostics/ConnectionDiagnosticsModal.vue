<script setup lang="ts">
import { computed } from 'vue'
import UiIcon from '../base/UiIcon.vue'
import { formatFileSize } from '../../utils/format'
import type {
  AdminDiagnostics,
  ConnectionPath,
  ConnectionState,
  NodePublicInfo,
} from '../../types'

interface Props {
  open: boolean
  embedded?: boolean
  state: ConnectionState
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
  loading?: boolean
  error?: string
}

const props = withDefaults(defineProps<Props>(), {
  embedded: false,
  loading: false,
  error: '',
})
const emit = defineEmits<{
  close: []
  refresh: []
  reconnect: []
  retry: []
  export: []
  clearCache: []
}>()

const stateLabel = computed(() => ({
  CONNECTING: '正在连接',
  AUTHENTICATING: '正在认证',
  SYNCING: '正在同步',
  ONLINE: '在线',
  DEGRADED: '降级可用',
  RECONNECTING: '正在重连',
  OFFLINE: '离线',
}[props.state]))
const pathLabel = computed(() => ({ LOCAL: '本机连接', LAN: '局域网连接', REMOTE: '远程连接' }[props.connectionPath]))
const modeLabel = computed(() => ({
  LOCAL_INDEPENDENT: '本地独立',
  LAN_FIRST: '局域网优先',
  HYBRID: '混合同步',
}[props.nodeInfo?.mode || 'LAN_FIRST']))
const recentConnections = computed(() => props.adminDiagnostics?.recentConnections.slice(0, 8) || [])

function formatDate(value: string | null | undefined): string {
  if (!value) return '暂无'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '暂无'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

function formatDuration(seconds = 0): string {
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  return [days ? `${days}天` : '', hours ? `${hours}小时` : '', `${minutes}分钟`].filter(Boolean).join(' ')
}

function closeFromBackdrop(): void {
  if (!props.embedded) emit('close')
}
</script>

<template>
  <div
    v-if="open"
    :class="embedded ? 'diagnostics-workspace' : 'diagnostics-backdrop'"
    :role="embedded ? undefined : 'presentation'"
    @click.self="closeFromBackdrop"
  >
    <section
      class="diagnostics-panel"
      :class="{ 'diagnostics-panel--embedded': embedded }"
      :role="embedded ? 'region' : 'dialog'"
      :aria-modal="embedded ? undefined : true"
      aria-labelledby="diagnostics-title"
    >
      <header class="diagnostics-header">
        <div>
          <p>CONNECTION DIAGNOSTICS</p>
          <h2 id="diagnostics-title">连接诊断</h2>
          <span>{{ nodeInfo?.nodeName || '当前 LanChat 节点' }} · {{ modeLabel }}</span>
        </div>
        <button v-if="!embedded" class="icon-button" type="button" aria-label="关闭诊断面板" @click="emit('close')">
          <UiIcon name="close" :size="17" />
        </button>
      </header>

      <div class="diagnostics-body">
        <p v-if="error" class="diagnostics-error" role="alert">{{ error }}</p>

        <div class="summary-grid">
          <article>
            <span>连接状态</span>
            <strong :class="`state-${state.toLowerCase()}`">{{ stateLabel }}</strong>
          </article>
          <article>
            <span>通信路径</span>
            <strong>{{ pathLabel }}</strong>
          </article>
          <article>
            <span>往返延迟</span>
            <strong>{{ latencyMs == null ? '等待心跳' : `${latencyMs} ms` }}</strong>
          </article>
          <article>
            <span>本地任务</span>
            <strong>{{ pendingCount }} 待发 · {{ failedCount }} 失败</strong>
          </article>
        </div>

        <section class="detail-section" aria-labelledby="client-diagnostics-title">
          <div class="section-heading">
            <h3 id="client-diagnostics-title">客户端与节点</h3>
            <button type="button" :disabled="loading" @click="emit('refresh')">{{ loading ? '刷新中…' : '刷新' }}</button>
          </div>
          <dl class="detail-list">
            <div><dt>组织</dt><dd>{{ nodeInfo?.organizationName || '未知' }}</dd></div>
            <div><dt>节点地址</dt><dd>{{ nodeAddress }}</dd></div>
            <div><dt>WebSocket</dt><dd>{{ webSocketAddress }}</dd></div>
            <div><dt>节点版本</dt><dd>{{ nodeInfo?.version || '未知' }}</dd></div>
            <div><dt>最近心跳</dt><dd>{{ formatDate(lastHeartbeatAt) }}</dd></div>
            <div><dt>最近同步</dt><dd>{{ formatDate(lastSyncAt) }}</dd></div>
            <div><dt>重连次数</dt><dd>{{ reconnectAttempts }}</dd></div>
          </dl>
          <div class="capability-row">
            <span
              v-for="(supported, capability) in browserCapabilities"
              :key="capability"
              :class="{ unsupported: !supported }"
            >{{ capability }} {{ supported ? '可用' : '不可用' }}</span>
          </div>
        </section>

        <section v-if="adminDiagnostics" class="detail-section admin-diagnostics" aria-labelledby="admin-diagnostics-title">
          <div class="section-heading">
            <h3 id="admin-diagnostics-title">管理员节点诊断</h3>
            <span>运行 {{ formatDuration(adminDiagnostics.uptimeSeconds) }}</span>
          </div>

          <div class="health-grid">
            <article>
              <span>数据库</span>
              <strong :class="{ down: adminDiagnostics.database.status !== 'UP' }">
                {{ adminDiagnostics.database.status }} · {{ adminDiagnostics.database.latencyMs ?? '—' }} ms
              </strong>
            </article>
            <article>
              <span>Redis</span>
              <strong :class="{ down: adminDiagnostics.redis.status !== 'UP' }">
                {{ adminDiagnostics.redis.status }} · {{ adminDiagnostics.redis.latencyMs ?? '—' }} ms
              </strong>
            </article>
            <article>
              <span>WebSocket</span>
              <strong>{{ adminDiagnostics.onlineUsers }} 用户 · {{ adminDiagnostics.webSocketConnections }} 连接</strong>
            </article>
            <article>
              <span>消息处理</span>
              <strong>{{ adminDiagnostics.averageEventProcessingMs }} ms 平均 · {{ adminDiagnostics.chatAcknowledgements }} ACK</strong>
            </article>
            <article>
              <span>文件存储</span>
              <strong :class="{ down: adminDiagnostics.storage.status !== 'UP' }">
                {{ adminDiagnostics.storage.usedPercent }}% · 可用 {{ formatFileSize(adminDiagnostics.storage.usableBytes) }}
              </strong>
            </article>
            <article>
              <span>JVM 堆内存</span>
              <strong>{{ formatFileSize(adminDiagnostics.jvm.heapUsedBytes) }} / {{ formatFileSize(adminDiagnostics.jvm.heapMaxBytes) }}</strong>
            </article>
          </div>

          <ul v-if="adminDiagnostics.warnings.length" class="warning-list">
            <li v-for="warning in adminDiagnostics.warnings" :key="warning">{{ warning }}</li>
          </ul>

          <div v-if="recentConnections.length" class="connection-events">
            <h4>最近连接生命周期</h4>
            <div v-for="event in recentConnections" :key="`${event.timestamp}-${event.event}-${event.userId || ''}`">
              <time>{{ formatDate(event.timestamp) }}</time>
              <strong>{{ event.event }}</strong>
              <span>{{ event.remoteAddress || '未知地址' }}{{ event.reason ? ` · ${event.reason}` : '' }}</span>
            </div>
          </div>
        </section>
      </div>

      <footer class="diagnostics-actions">
        <button type="button" @click="emit('reconnect')">手动重连</button>
        <button type="button" :disabled="pendingCount === 0 && failedCount === 0" @click="emit('retry')">重试全部任务</button>
        <button type="button" @click="emit('clearCache')">清理浏览器缓存</button>
        <button class="primary-action" type="button" @click="emit('export')">导出脱敏诊断</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.diagnostics-backdrop { position: fixed; z-index: 140; inset: 0; display: grid; padding: 24px; place-items: center; background: rgba(18, 29, 43, .34); backdrop-filter: blur(12px); }
.diagnostics-workspace { width: 100%; height: 100%; min-width: 0; min-height: 0; background: var(--surface); }
.diagnostics-panel { display: grid; width: min(880px, 100%); max-height: min(860px, calc(100dvh - 48px)); grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border: 1px solid var(--glass-border); border-radius: 24px; color: var(--ink); background: var(--surface-raise); box-shadow: 0 28px 80px rgba(18, 38, 64, .28); }
.diagnostics-panel--embedded { width: 100%; height: 100%; max-height: none; border: 0; border-radius: 0; box-shadow: none; }
.diagnostics-header { display: flex; padding: 22px 24px 18px; align-items: flex-start; justify-content: space-between; border-bottom: 1px solid var(--separator); }
.diagnostics-header p { margin: 0 0 6px; color: var(--blue); font-size: 10px; font-weight: 750; letter-spacing: .1em; }
.diagnostics-header h2 { margin: 0; font-size: 23px; letter-spacing: -.035em; }
.diagnostics-header span { display: block; margin-top: 5px; color: var(--ink-soft); font-size: 11px; }
.icon-button { display: grid; width: 34px; height: 34px; padding: 0; place-items: center; border: 0; border-radius: 10px; color: var(--ink-soft); background: var(--fill); cursor: pointer; }
.diagnostics-body { padding: 20px 24px 24px; overflow-y: auto; }
.diagnostics-error { padding: 10px 12px; margin: 0 0 14px; border-radius: 10px; color: var(--coral); font-size: 11px; background: color-mix(in srgb, var(--coral) 9%, transparent); }
.summary-grid,
.health-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.summary-grid article,
.health-grid article { display: grid; min-width: 0; padding: 13px; gap: 7px; border: 1px solid var(--separator); border-radius: 13px; background: var(--surface); }
.summary-grid span,
.health-grid span { color: var(--ink-faint); font-size: 9px; font-weight: 650; }
.summary-grid strong,
.health-grid strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.summary-grid .state-online { color: var(--green); }
.summary-grid .state-offline,
.summary-grid .state-degraded,
.health-grid strong.down { color: var(--coral); }
.detail-section { padding-top: 22px; margin-top: 20px; border-top: 1px solid var(--separator); }
.section-heading { display: flex; margin-bottom: 12px; align-items: center; justify-content: space-between; gap: 12px; }
.section-heading h3 { margin: 0; font-size: 14px; }
.section-heading button,
.section-heading span { border: 0; color: var(--ink-faint); font: inherit; font-size: 10px; background: none; }
.section-heading button { color: var(--blue); cursor: pointer; }
.detail-list { display: grid; margin: 0; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.detail-list div { display: grid; min-width: 0; padding: 10px 0; grid-template-columns: 92px minmax(0, 1fr); border-bottom: 1px solid var(--separator); }
.detail-list dt { color: var(--ink-faint); font-size: 10px; }
.detail-list dd { min-width: 0; margin: 0; overflow: hidden; color: var(--ink-soft); font-size: 10px; text-align: right; text-overflow: ellipsis; white-space: nowrap; }
.capability-row { display: flex; margin-top: 14px; flex-wrap: wrap; gap: 7px; }
.capability-row span { padding: 4px 8px; border-radius: 999px; color: var(--green); font-size: 9px; background: color-mix(in srgb, var(--green) 10%, transparent); }
.capability-row span.unsupported { color: var(--coral); background: color-mix(in srgb, var(--coral) 9%, transparent); }
.health-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.warning-list { padding: 10px 12px 10px 28px; margin: 13px 0 0; border-radius: 12px; color: #a76300; font-size: 10px; line-height: 1.7; background: rgba(255, 159, 10, .1); }
.connection-events { margin-top: 16px; }
.connection-events h4 { margin: 0 0 8px; color: var(--ink-soft); font-size: 11px; }
.connection-events > div { display: grid; padding: 7px 0; grid-template-columns: 108px 116px minmax(0, 1fr); gap: 9px; border-top: 1px solid var(--separator); font-size: 9px; }
.connection-events time,
.connection-events span { overflow: hidden; color: var(--ink-faint); text-overflow: ellipsis; white-space: nowrap; }
.diagnostics-actions { display: flex; padding: 15px 24px; flex-wrap: wrap; justify-content: flex-end; gap: 8px; border-top: 1px solid var(--separator); background: var(--surface-glass); }
.diagnostics-actions button { min-height: 34px; padding: 0 12px; border: 0; border-radius: 10px; color: var(--ink-soft); font: inherit; font-size: 10px; font-weight: 650; background: var(--fill); cursor: pointer; }
.diagnostics-actions button:disabled { opacity: .42; cursor: not-allowed; }
.diagnostics-actions .primary-action { color: white; background: var(--blue); }

@media (max-width: 720px) {
  .diagnostics-backdrop { padding: 0; align-items: end; }
  .diagnostics-panel { width: 100%; max-height: 94dvh; border-radius: 24px 24px 0 0; }
  .diagnostics-panel--embedded { height: 100%; max-height: none; border-radius: 0; }
  .summary-grid,
  .health-grid { grid-template-columns: 1fr 1fr; }
  .detail-list { grid-template-columns: 1fr; }
  .diagnostics-actions { justify-content: stretch; }
  .diagnostics-actions button { flex: 1 1 42%; }
}
</style>
