<script setup lang="ts">
import { computed } from 'vue'
import type { ConnectionPath, ConnectionState } from '../../types'

interface Props {
  state: ConnectionState
  pendingCount: number
  failedCount: number
  reconnectAttempts?: number
  latencyMs?: number | null
  nodeName?: string
  connectionPath?: ConnectionPath
}

const props = withDefaults(defineProps<Props>(), {
  reconnectAttempts: 0,
  latencyMs: null,
  nodeName: '当前节点',
  connectionPath: 'LAN',
})
const emit = defineEmits<{
  reconnect: []
  retry: []
  details: []
}>()

const pathCopy = computed(() => ({
  LOCAL: '本机',
  LAN: '局域网',
  REMOTE: '远程',
}[props.connectionPath]))

const stateCopy = computed(() => ({
  CONNECTING: '正在连接局域网节点',
  AUTHENTICATING: '正在验证设备会话',
  SYNCING: '正在补齐遗漏消息',
  ONLINE: props.latencyMs == null ? '节点在线' : `节点在线 · ${props.latencyMs} ms`,
  DEGRADED: '连接可用，但部分同步尚未完成',
  RECONNECTING: `正在重连${props.reconnectAttempts ? ` · 第 ${props.reconnectAttempts} 次` : ''}`,
  OFFLINE: '当前离线，文本消息会安全保存在本机',
}[props.state]))

const tone = computed(() => {
  if (props.failedCount > 0) return 'danger'
  if (props.state === 'ONLINE') return 'online'
  if (props.state === 'DEGRADED') return 'warning'
  return 'offline'
})
</script>

<template>
  <div class="connection-status" :class="`connection-status--${tone}`" role="status">
    <span class="status-dot" aria-hidden="true" />
    <button class="status-copy" type="button" aria-label="打开连接诊断" @click="emit('details')">
      <strong>{{ nodeName }}</strong>
      <span>{{ pathCopy }} · {{ stateCopy }}</span>
    </button>
    <span v-if="pendingCount > 0" class="status-count">待发送 {{ pendingCount }}</span>
    <span v-if="failedCount > 0" class="status-count status-count--failed">失败 {{ failedCount }}</span>
    <button v-if="failedCount > 0" type="button" @click="emit('retry')">重试失败项</button>
    <button v-if="state === 'OFFLINE' || state === 'DEGRADED'" type="button" @click="emit('reconnect')">
      立即重连
    </button>
    <button class="details-button" type="button" @click="emit('details')">诊断</button>
  </div>
</template>

<style scoped>
.connection-status {
  display: flex;
  min-height: 34px;
  padding: 6px 18px;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--separator);
  color: var(--ink-soft);
  font-size: 11px;
  background: var(--surface-glass);
}
.status-dot { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: var(--ink-faint); }
.status-copy { display: flex; min-width: 0; padding: 0; align-items: baseline; gap: 5px; overflow: hidden; border: 0; color: inherit; font: inherit; background: none; cursor: pointer; }
.status-copy strong,
.status-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-copy strong { color: var(--ink-soft); font-weight: 700; }
.status-count { padding: 2px 7px; border-radius: 999px; color: var(--blue); background: rgba(0, 122, 255, .09); }
.status-count--failed { color: var(--coral); background: rgba(255, 59, 48, .09); }
.connection-status > button:not(.status-copy) { padding: 3px 8px; border: 0; border-radius: 8px; color: var(--blue); font: inherit; font-weight: 650; background: var(--fill); cursor: pointer; }
.connection-status .details-button { margin-left: auto; }
.connection-status--online .status-dot { background: var(--green); }
.connection-status--warning .status-dot { background: #ff9f0a; }
.connection-status--danger .status-dot { background: var(--coral); }

@media (max-width: 760px) {
  .connection-status { padding-inline: 13px; }
  .status-count { display: none; }
}
</style>
