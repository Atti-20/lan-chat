<script setup lang="ts">
import { computed } from 'vue'
import UiIcon from '../base/UiIcon.vue'
import { useNodeDiscovery } from '../../composables/useNodeDiscovery'
import type { DiscoveredNode } from '../../types'

const discovery = useNodeDiscovery()
const statusCopy = computed(() => {
  if (discovery.loading.value && !discovery.nodeInfo.value) return '正在扫描局域网…'
  if (discovery.error.value) return discovery.error.value
  if (!discovery.nodeInfo.value?.discoveryEnabled) return '当前节点未启用 mDNS 扫描'
  return discovery.peerCount.value > 0
    ? `发现 ${discovery.peerCount.value} 个可连接节点`
    : '正在监听同一局域网内的节点'
})

function modeLabel(mode: string): string {
  return ({
    LOCAL_INDEPENDENT: '本地独立',
    LAN_FIRST: '局域网优先',
    HYBRID: '混合同步',
  } as Record<string, string>)[mode] || mode
}

function selectNode(node: DiscoveredNode): void {
  discovery.openNode(node)
}
</script>

<template>
  <section class="node-discovery" aria-labelledby="node-discovery-title">
    <header>
      <div class="discovery-icon" aria-hidden="true">
        <UiIcon name="monitor" :size="18" />
      </div>
      <div>
        <h2 id="node-discovery-title">局域网节点</h2>
        <p aria-live="polite">{{ statusCopy }}</p>
      </div>
      <button type="button" :disabled="discovery.loading.value" @click="discovery.refresh">
        {{ discovery.loading.value ? '扫描中' : '重新扫描' }}
      </button>
    </header>

    <div v-if="discovery.nodes.value.length" class="node-list">
      <button
        v-for="node in discovery.nodes.value"
        :key="node.nodeId"
        type="button"
        class="node-item"
        :class="{ current: node.current }"
        :disabled="node.current"
        @click="selectNode(node)"
      >
        <span class="node-signal" aria-hidden="true" />
        <span class="node-copy">
          <strong>{{ node.nodeName }}</strong>
          <small>{{ node.organizationName }} · {{ modeLabel(node.mode) }} · v{{ node.version }}</small>
        </span>
        <span class="node-action">{{ node.current ? '当前节点' : '连接' }}</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.node-discovery { width: min(560px, 100%); padding: 16px; border: 1px solid var(--glass-border); border-radius: 18px; background: var(--surface-glass); box-shadow: 0 10px 28px var(--shadow-color); backdrop-filter: blur(16px) saturate(145%); }
.node-discovery header { display: flex; align-items: center; gap: 10px; }
.discovery-icon { display: grid; width: 36px; height: 36px; flex: 0 0 auto; place-items: center; border-radius: 11px; color: var(--blue); background: var(--active); }
.node-discovery header > div:nth-child(2) { min-width: 0; flex: 1; }
.node-discovery h2 { margin: 0; font-size: 13px; }
.node-discovery p { margin: 3px 0 0; overflow: hidden; color: var(--ink-faint); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.node-discovery header > button { padding: 5px 8px; border: 0; border-radius: 8px; color: var(--blue); font: inherit; font-size: 9px; background: var(--fill); cursor: pointer; }
.node-discovery header > button:disabled { opacity: .5; }
.node-list { display: grid; max-height: 178px; margin-top: 12px; gap: 6px; overflow-y: auto; }
.node-item { display: flex; width: 100%; min-height: 48px; padding: 8px 9px; align-items: center; gap: 9px; border: 1px solid transparent; border-radius: 12px; color: var(--ink); text-align: left; background: var(--surface); cursor: pointer; }
.node-item:hover:not(:disabled) { border-color: color-mix(in srgb, var(--blue) 26%, transparent); background: var(--active); }
.node-item.current { background: color-mix(in srgb, var(--green) 7%, var(--surface)); cursor: default; }
.node-signal { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 4px color-mix(in srgb, var(--green) 10%, transparent); }
.node-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.node-copy strong,
.node-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-copy strong { font-size: 10px; }
.node-copy small { color: var(--ink-faint); font-size: 8px; }
.node-action { color: var(--blue); font-size: 9px; font-weight: 700; }
.node-item.current .node-action { color: var(--green); }

@media (max-width: 860px) {
  .node-discovery { display: none; }
}
</style>
