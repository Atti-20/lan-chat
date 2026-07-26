<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeLogLevelFilter, RuntimeLogSnapshot } from '../../../types'
import UiIcon from '../../base/UiIcon.vue'

interface Props {
  snapshot: RuntimeLogSnapshot | null
  level: RuntimeLogLevelFilter
  keyword: string
  loading: boolean
  exporting: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  updateLevel: [level: RuntimeLogLevelFilter]
  updateKeyword: [keyword: string]
  refresh: []
  export: []
}>()

const counts = computed(() => ({
  ERROR: props.snapshot?.levelCounts.ERROR ?? 0,
  WARN: props.snapshot?.levelCounts.WARN ?? 0,
  INFO: props.snapshot?.levelCounts.INFO ?? 0,
}))
const fileSummary = computed(() => {
  if (!props.snapshot) return '等待读取日志文件'
  return `${props.snapshot.fileName} · ${formatBytes(props.snapshot.fileSizeBytes)}`
})
const updatedLabel = computed(() => {
  const value = props.snapshot?.updatedAt
  if (!value) return '尚无更新时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `文件更新于 ${new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)}`
})

function updateLevel(event: Event): void {
  emit('updateLevel', (event.target as HTMLSelectElement).value as RuntimeLogLevelFilter)
}

function updateKeyword(event: Event): void {
  emit('updateKeyword', (event.target as HTMLInputElement).value)
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section class="log-toolbar" aria-labelledby="runtime-log-title">
    <header class="toolbar-heading">
      <div class="heading-copy">
        <p>NODE OUTPUT / LIVE</p>
        <h2 id="runtime-log-title">运行日志</h2>
        <span>查看启动状态、运行警告与服务端错误</span>
      </div>
      <div class="toolbar-actions">
        <button type="button" :disabled="loading" @click="emit('refresh')">
          <UiIcon name="refresh" :size="16" />
          {{ loading ? '读取中' : '刷新' }}
        </button>
        <button class="export-button" type="button" :disabled="exporting || !snapshot?.available" @click="emit('export')">
          <UiIcon name="download" :size="16" />
          {{ exporting ? '导出中' : '导出完整日志' }}
        </button>
      </div>
    </header>

    <div class="incident-rail" aria-label="日志级别统计">
      <span class="incident incident--error"><i />错误 <strong>{{ counts.ERROR }}</strong></span>
      <span class="incident incident--warn"><i />警告 <strong>{{ counts.WARN }}</strong></span>
      <span class="incident incident--info"><i />信息 <strong>{{ counts.INFO }}</strong></span>
      <span class="file-summary">{{ fileSummary }}</span>
    </div>

    <form class="log-filters" role="search" @submit.prevent="emit('refresh')">
      <label class="level-field">
        <span>日志级别</span>
        <select :value="level" aria-label="筛选日志级别" @change="updateLevel">
          <option value="ALL">全部级别</option>
          <option value="ERROR">仅错误</option>
          <option value="WARN">仅警告</option>
          <option value="INFO">仅信息</option>
          <option value="DEBUG">仅调试</option>
          <option value="TRACE">仅追踪</option>
        </select>
      </label>
      <label class="keyword-field">
        <span>内容检索</span>
        <input
          :value="keyword"
          maxlength="80"
          type="search"
          placeholder="错误内容、记录器或请求 ID"
          @input="updateKeyword"
        >
      </label>
      <button class="apply-button" type="submit" :disabled="loading">应用筛选</button>
    </form>

    <footer class="toolbar-meta">
      <span>{{ updatedLabel }}</span>
      <span>已扫描 {{ snapshot?.scannedEntries ?? 0 }} 条</span>
      <span>每 10 秒自动更新</span>
    </footer>
  </section>
</template>

<style scoped>
.log-toolbar {
  flex: none;
  border-bottom: 1px solid var(--separator);
  background: var(--surface-raise);
}
.toolbar-heading {
  display: flex;
  min-height: 94px;
  padding: 20px 24px 16px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.heading-copy { min-width: 0; }
.heading-copy p { margin: 0 0 5px; color: var(--blue); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-micro); font-weight: 750; letter-spacing: .12em; }
.heading-copy h2 { margin: 0; font-size: 23px; letter-spacing: -.04em; }
.heading-copy span { display: block; margin-top: 5px; color: var(--ink-soft); font-size: 11px; }
.toolbar-actions { display: flex; flex: none; gap: 8px; }
.toolbar-actions button,
.apply-button {
  display: inline-flex;
  min-height: 36px;
  padding: 0 12px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  border-radius: 10px;
  color: var(--blue);
  font: inherit;
  font-size: var(--font-caption);
  font-weight: 700;
  background: var(--active);
  cursor: pointer;
}
.toolbar-actions .export-button { color: white; background: var(--blue); }
.toolbar-actions button:disabled,
.apply-button:disabled { opacity: .5; cursor: default; }
.incident-rail {
  display: flex;
  min-height: 39px;
  padding: 0 24px;
  align-items: center;
  gap: 18px;
  border-top: 1px solid var(--separator);
  border-bottom: 1px solid var(--separator);
  background: color-mix(in srgb, var(--surface) 70%, transparent);
}
.incident { display: inline-flex; align-items: center; gap: 5px; color: var(--ink-soft); font-size: var(--font-caption); }
.incident i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.incident strong { color: var(--ink); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-caption); }
.incident--error { color: var(--coral); }
.incident--warn { color: #d97706; }
.incident--info { color: var(--blue); }
.file-summary { margin-left: auto; overflow: hidden; color: var(--ink-faint); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-micro); text-overflow: ellipsis; white-space: nowrap; }
.log-filters {
  display: grid;
  padding: 13px 24px;
  grid-template-columns: 160px minmax(220px, 1fr) auto;
  align-items: end;
  gap: 10px;
}
.log-filters label { display: grid; gap: 6px; }
.log-filters label > span { color: var(--ink-soft); font-size: var(--font-micro); font-weight: 700; }
.log-filters select,
.log-filters input {
  width: 100%;
  min-width: 0;
  height: 36px;
  border: 1px solid var(--separator);
  border-radius: 10px;
  color: var(--ink);
  font: inherit;
  font-size: var(--font-caption);
  background: var(--surface);
  outline: none;
}
.log-filters select { padding: 0 10px; }
.log-filters input { padding: 0 11px; }
.log-filters select:focus,
.log-filters input:focus { border-color: color-mix(in srgb, var(--blue) 55%, transparent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--blue) 10%, transparent); }
.apply-button { color: var(--ink); background: var(--fill); }
.toolbar-meta { display: flex; padding: 0 24px 12px; align-items: center; gap: 14px; color: var(--ink-faint); font-size: var(--font-micro); }
.toolbar-meta span + span::before { margin-right: 14px; content: "·"; }

@media (max-width: 760px) {
  .toolbar-heading { min-height: 0; padding: 16px; align-items: flex-start; }
  .heading-copy h2 { font-size: 20px; }
  .heading-copy span { display: none; }
  .toolbar-actions button { width: 36px; padding: 0; }
  .toolbar-actions button :deep(.ui-icon) { margin: 0; }
  .toolbar-actions button { font-size: 0; }
  .incident-rail { padding: 0 16px; gap: 12px; }
  .file-summary { display: none; }
  .log-filters { padding: 12px 16px; grid-template-columns: 116px minmax(0, 1fr); }
  .apply-button { grid-column: 1 / -1; }
  .toolbar-meta { padding: 0 16px 11px; }
  .toolbar-meta span:nth-child(2) { display: none; }
}
</style>
