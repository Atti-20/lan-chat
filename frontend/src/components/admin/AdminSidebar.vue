<script setup lang="ts">
import { computed } from 'vue'
import type { ConnectionState } from '../../types'
import UiIcon, { type IconName } from '../base/UiIcon.vue'
import type { AdminModule } from './adminNavigation'

interface Props {
  selected: AdminModule | null
  accountCount?: number
  nodeName?: string
  connectionState: ConnectionState
}

const props = withDefaults(defineProps<Props>(), {
  accountCount: undefined,
  nodeName: '当前 LanChat 节点',
})
const emit = defineEmits<{
  select: [module: AdminModule]
}>()

interface ModuleItem {
  id: AdminModule
  title: string
  description: string
  icon: IconName
}

const modules: ModuleItem[] = [
  {
    id: 'accounts',
    title: '账号管理',
    description: '创建账号、封禁、禁言与删除',
    icon: 'users',
  },
  {
    id: 'diagnostics',
    title: '连接诊断',
    description: '节点依赖、连接与运行状态',
    icon: 'activity',
  },
  {
    id: 'logs',
    title: '运行日志',
    description: '启动输出、运行警告与错误追踪',
    icon: 'terminal',
  },
]

const connectionLabel = computed(() => ({
  CONNECTING: '连接中',
  AUTHENTICATING: '认证中',
  SYNCING: '同步中',
  ONLINE: '运行正常',
  DEGRADED: '降级运行',
  RECONNECTING: '重连中',
  OFFLINE: '当前离线',
}[props.connectionState]))
const connectionHealthy = computed(() => ['ONLINE', 'SYNCING'].includes(props.connectionState))
</script>

<template>
  <aside class="admin-sidebar" aria-label="管理模块">
    <header class="admin-sidebar-header">
      <p>节点控制台</p>
      <h1>管理</h1>
      <span>{{ nodeName }}</span>
    </header>

    <nav class="module-list" aria-label="管理功能">
      <button
        v-for="item in modules"
        :key="item.id"
        class="module-item"
        :class="{ 'module-item--active': selected === item.id }"
        type="button"
        :aria-current="selected === item.id ? 'page' : undefined"
        @click="emit('select', item.id)"
      >
        <span class="module-icon"><UiIcon :name="item.icon" :size="20" /></span>
        <span class="module-copy">
          <strong>{{ item.title }}</strong>
          <small>{{ item.description }}</small>
        </span>
        <span v-if="item.id === 'accounts'" class="module-meta">
          {{ accountCount == null ? '待载入' : `${accountCount} 个` }}
        </span>
        <span v-else-if="item.id === 'diagnostics'" class="module-meta module-health" :class="{ healthy: connectionHealthy }">
          <i />{{ connectionLabel }}
        </span>
        <span v-else class="module-meta">实时</span>
      </button>
    </nav>

    <footer class="admin-sidebar-footer">
      <UiIcon name="lock" :size="16" />
      <span>管理操作仅对根管理员开放</span>
    </footer>
  </aside>
</template>

<style scoped>
.admin-sidebar {
  display: flex;
  width: 320px;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  border-right: 1px solid var(--separator);
  background: var(--surface-raise);
}
.admin-sidebar-header { padding: 22px 18px 17px; border-bottom: 1px solid var(--separator); }
.admin-sidebar-header p { margin: 0 0 3px; color: var(--blue); font-size: 11px; font-weight: 600; }
.admin-sidebar-header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -.03em; }
.admin-sidebar-header span { display: block; margin-top: 7px; overflow: hidden; color: var(--ink-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.module-list { display: grid; padding: 10px 8px; gap: 4px; }
.module-item {
  display: grid;
  width: 100%;
  min-height: 78px;
  padding: 11px 10px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  border: 0;
  border-radius: 12px;
  color: var(--ink);
  text-align: left;
  background: transparent;
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease;
}
.module-item:hover { background: var(--hover); }
.module-item--active { background: var(--active); }
.module-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; color: var(--blue); background: var(--fill); }
.module-item--active .module-icon { color: white; background: var(--blue); }
.module-copy { display: grid; min-width: 0; gap: 5px; }
.module-copy strong { font-size: 13px; }
.module-copy small { overflow: hidden; color: var(--ink-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.module-meta { align-self: start; padding-top: 3px; color: var(--ink-faint); font-size: 9px; white-space: nowrap; }
.module-health { display: inline-flex; align-items: center; gap: 5px; color: var(--coral); }
.module-health i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.module-health.healthy { color: var(--green); }
.admin-sidebar-footer { display: flex; padding: 15px 18px; margin-top: auto; align-items: center; gap: 8px; border-top: 1px solid var(--separator); color: var(--ink-faint); font-size: 9px; }
.admin-sidebar-footer .ui-icon { color: var(--blue); }
.module-item:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 55%, transparent); outline-offset: -2px; }

@media (max-width: 1020px) {
  .admin-sidebar { width: 294px; }
}
@media (max-width: 760px) {
  .admin-sidebar {
    width: 100%;
    padding-bottom: 84px;
    border: 0;
    background: var(--surface);
  }
  .admin-sidebar-header { padding-top: max(20px, env(safe-area-inset-top)); }
}
</style>
