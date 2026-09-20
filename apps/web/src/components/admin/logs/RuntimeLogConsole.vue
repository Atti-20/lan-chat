<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef } from 'vue'
import { useRuntimeLogs } from '../../../composables/useRuntimeLogs'
import type { RuntimeLogLevelFilter } from '../../../types'
import RuntimeLogList from './RuntimeLogList.vue'
import RuntimeLogToolbar from './RuntimeLogToolbar.vue'

const { snapshot, loading, exporting, error, load, exportLog } = useRuntimeLogs()
const level = shallowRef<RuntimeLogLevelFilter>('ALL')
const keyword = shallowRef('')
const appliedKeyword = shallowRef('')
let refreshTimer: number | undefined

onMounted(() => {
  void refresh()
  refreshTimer = window.setInterval(() => {
    if (!document.hidden) void refresh()
  }, 10_000)
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
})

function refresh(): Promise<void> {
  return load({ limit: 300, level: level.value, keyword: appliedKeyword.value })
}

function applyFilters(): void {
  appliedKeyword.value = keyword.value.trim()
  void refresh()
}

function updateLevel(next: RuntimeLogLevelFilter): void {
  level.value = next
  void refresh()
}
</script>

<template>
  <div class="runtime-log-console">
    <RuntimeLogToolbar
      :snapshot="snapshot"
      :level="level"
      :keyword="keyword"
      :loading="loading"
      :exporting="exporting"
      @update-level="updateLevel"
      @update-keyword="keyword = $event"
      @refresh="applyFilters"
      @export="exportLog"
    />
    <RuntimeLogList
      :entries="snapshot?.entries ?? []"
      :loading="loading || (!snapshot && !error)"
      :error="error"
      :available="snapshot?.available ?? true"
      :notice="snapshot?.notice ?? '正在准备运行日志。'"
      @retry="refresh"
    />
  </div>
</template>

<style scoped>
.runtime-log-console {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  background: var(--surface);
  overflow: hidden;
}
</style>
