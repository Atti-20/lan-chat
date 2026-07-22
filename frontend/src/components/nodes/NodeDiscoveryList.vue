<script setup lang="ts">
import type { DesktopNode } from '../../platform/nativeBridge'

defineProps<{
  nodes: readonly DesktopNode[]
}>()

const emit = defineEmits<{
  select: [node: DesktopNode]
}>()

function modeLabel(mode: string): string {
  return ({
    LOCAL_INDEPENDENT: '本地独立',
    LAN_FIRST: '局域网优先',
    HYBRID: '混合同步',
  } as Record<string, string>)[mode] || mode
}

function healthLabel(node: DesktopNode): string {
  if (node.current) return '当前节点'
  return ({
    HEALTHY: node.latencyMs == null ? '可连接' : `${node.latencyMs} ms`,
    PROBING: '验证中',
    DEGRADED: '不稳定',
    UNKNOWN: '待验证',
    OFFLINE: '离线',
  } satisfies Record<DesktopNode['health'], string>)[node.health]
}

function sourceLabel(source: DesktopNode['source']): string {
  return ({
    MDNS: '本机发现',
    SERVER_FALLBACK: '节点推荐',
    CACHE: '历史缓存',
    MANUAL: '手动添加',
  } satisfies Record<DesktopNode['source'], string>)[source]
}
</script>

<template>
  <div class="node-list">
    <button
      v-for="node in nodes"
      :key="`${node.nodeId}:${node.appUrl}`"
      type="button"
      class="node-item apple-list-row"
      :class="[`health-${node.health.toLowerCase()}`, { current: node.current }]"
      :disabled="node.current || node.health === 'OFFLINE'"
      @click="emit('select', node)"
    >
      <span class="node-signal" aria-hidden="true" />
      <span class="node-copy">
        <strong>{{ node.nodeName }}</strong>
        <small>
          {{ node.organizationName }} · {{ modeLabel(node.mode) }} ·
          {{ sourceLabel(node.source) }}
        </small>
      </span>
      <span class="node-action">{{ healthLabel(node) }}</span>
    </button>
  </div>
</template>

<style scoped>
.node-list { display: grid; max-height: 178px; margin-top: 12px; gap: 6px; overflow-y: auto; }
.node-item { display: flex; width: 100%; min-height: 48px; padding: 8px 9px; align-items: center; gap: 9px; border: 1px solid transparent; border-radius: 12px; color: var(--ink); text-align: left; background: var(--surface); cursor: pointer; }
.node-item:hover:not(:disabled) { border-color: color-mix(in srgb, var(--blue) 26%, transparent); background: var(--active); }
.node-item:disabled { cursor: default; }
.node-item.current { background: color-mix(in srgb, var(--green) 7%, var(--surface)); }
.node-signal { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 4px color-mix(in srgb, var(--green) 10%, transparent); }
.health-probing .node-signal,
.health-unknown .node-signal { background: var(--blue); box-shadow: 0 0 0 4px color-mix(in srgb, var(--blue) 10%, transparent); }
.health-degraded .node-signal { background: #ff9f0a; box-shadow: 0 0 0 4px rgba(255, 159, 10, .1); }
.health-offline { opacity: .55; }
.health-offline .node-signal { background: var(--ink-faint); box-shadow: none; }
.node-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.node-copy strong,
.node-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-copy strong { font-size: 10px; }
.node-copy small { color: var(--ink-faint); font-size: 8px; }
.node-action { color: var(--blue); font-size: 9px; font-weight: 700; white-space: nowrap; }
.node-item.current .node-action { color: var(--green); }
</style>
