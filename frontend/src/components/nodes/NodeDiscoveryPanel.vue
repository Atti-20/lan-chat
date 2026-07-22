<script setup lang="ts">
import { computed } from 'vue'
import UiIcon from '../base/UiIcon.vue'
import { useNodeDiscovery } from '../../composables/useNodeDiscovery'
import type { DesktopNode } from '../../platform/nativeBridge'
import ManualNodeForm from './ManualNodeForm.vue'
import NodeDiscoveryList from './NodeDiscoveryList.vue'

const discovery = useNodeDiscovery()
const statusCopy = computed(() => {
  if (discovery.loading.value && discovery.nodes.value.length === 0) return '正在扫描局域网…'
  if (discovery.error.value) return discovery.error.value
  if (discovery.desktop) {
    return discovery.peerCount.value > 0
      ? `原生扫描发现 ${discovery.peerCount.value} 个节点`
      : '正在通过 mDNS 监听局域网节点'
  }
  if (discovery.mobile) return discovery.peerCount.value > 0
    ? `发现 ${discovery.peerCount.value} 个可连接节点`
    : '正在扫描局域网节点'
  if (!discovery.nodeInfo.value?.discoveryEnabled) return '当前节点未启用 mDNS 扫描'
  return discovery.peerCount.value > 0
    ? `发现 ${discovery.peerCount.value} 个可连接节点`
    : '正在监听同一局域网内的节点'
})

function selectNode(node: DesktopNode): void {
  void discovery.openNode(node)
}
</script>

<template>
  <section
    class="node-discovery apple-float-surface"
    :class="{ 'node-discovery--mobile': discovery.mobile }"
    aria-labelledby="node-discovery-title"
  >
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

    <NodeDiscoveryList
      v-if="discovery.nodes.value.length"
      :nodes="discovery.nodes.value"
      @select="selectNode"
    />
    <ManualNodeForm
      v-if="discovery.desktop || discovery.mobile"
      :busy="discovery.addingManual.value"
      @submit="discovery.addManualNode"
    />
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

@media (max-width: 860px) {
  .node-discovery { display: none; }
  .node-discovery--mobile { display: block; }
}
</style>
