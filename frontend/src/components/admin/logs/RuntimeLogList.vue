<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeLogEntry } from '../../../types'
import UiIcon from '../../base/UiIcon.vue'

interface Props {
  entries: readonly RuntimeLogEntry[]
  loading: boolean
  error: string | null
  available: boolean
  notice: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  retry: []
}>()

const levelLabels = {
  TRACE: '追踪',
  DEBUG: '调试',
  INFO: '信息',
  WARN: '警告',
  ERROR: '错误',
} as const
const levelClasses = {
  TRACE: 'log-entry--trace',
  DEBUG: 'log-entry--debug',
  INFO: 'log-entry--info',
  WARN: 'log-entry--warn',
  ERROR: 'log-entry--error',
} as const
const displayEntries = computed(() => props.entries.map((entry) => ({
  ...entry,
  levelLabel: levelLabels[entry.level],
  levelClass: levelClasses[entry.level],
  displayTime: formatTimestamp(entry.timestamp),
  shortLogger: entry.logger.split('.').pop() || entry.logger,
})))

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}
</script>

<template>
  <section class="log-stream" aria-label="运行日志内容" :aria-busy="loading">
    <div v-if="loading && entries.length === 0" class="stream-state">
      <span class="state-spinner" />
      <strong>正在读取运行日志</strong>
      <p>正在解析启动信息、警告和错误记录。</p>
    </div>

    <div v-else-if="error && entries.length === 0" class="stream-state stream-state--error" role="alert">
      <UiIcon name="activity" :size="25" />
      <strong>日志读取失败</strong>
      <p>{{ error }}</p>
      <button type="button" @click="emit('retry')">重新读取</button>
    </div>

    <div v-else-if="!available" class="stream-state">
      <UiIcon name="terminal" :size="25" />
      <strong>尚无日志内容</strong>
      <p>{{ notice }}</p>
      <button type="button" @click="emit('retry')">再次检查</button>
    </div>

    <template v-else>
      <div class="stream-notice" :class="{ 'stream-notice--loading': loading }" role="status">
        <span><i />{{ loading ? '正在同步最新输出' : notice }}</span>
        <span v-if="error" class="notice-error">本次刷新失败：{{ error }}</span>
      </div>

      <div v-if="displayEntries.length === 0" class="stream-state stream-state--compact">
        <UiIcon name="search" :size="24" />
        <strong>没有符合条件的记录</strong>
        <p>调整日志级别或检索词后重新筛选。</p>
      </div>

      <ol v-else class="log-list">
        <li v-for="entry in displayEntries" :key="entry.sequence">
          <article class="log-entry" :class="entry.levelClass">
            <header class="entry-header">
              <span class="level-badge">{{ entry.levelLabel }}</span>
              <time :datetime="entry.timestamp" :title="entry.timestamp">{{ entry.displayTime }}</time>
              <span class="logger-name" :title="entry.logger">{{ entry.shortLogger }}</span>
              <span class="request-id" :title="entry.requestId">
                {{ entry.requestId === 'system' ? '系统进程' : entry.requestId }}
              </span>
            </header>

            <p class="entry-message">{{ entry.message || '（无消息正文）' }}</p>

            <aside v-if="entry.explanation" class="entry-explanation" role="note">
              <strong>{{ entry.level === 'ERROR' ? '错误说明' : '警告说明' }}</strong>
              <p>{{ entry.explanation }}</p>
            </aside>

            <details v-if="entry.details" class="entry-details">
              <summary>{{ entry.level === 'ERROR' ? '展开错误堆栈' : '展开详细信息' }}</summary>
              <pre>{{ entry.details }}</pre>
            </details>

            <footer class="entry-footer">线程 {{ entry.thread || 'unknown' }}</footer>
          </article>
        </li>
      </ol>
    </template>
  </section>
</template>

<style scoped>
.log-stream {
  position: relative;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  background: var(--surface);
}
.stream-notice {
  position: sticky;
  z-index: 2;
  top: 0;
  display: flex;
  min-height: 36px;
  padding: 8px 24px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--separator);
  color: var(--ink-faint);
  font-size: var(--font-micro);
  background: color-mix(in srgb, var(--surface-raise) 92%, transparent);
  backdrop-filter: blur(14px);
}
.stream-notice span { display: inline-flex; align-items: center; gap: 7px; }
.stream-notice i { width: 6px; height: 6px; border-radius: 50%; background: var(--green); }
.stream-notice--loading i { background: var(--blue); animation: notice-pulse 1s ease-in-out infinite; }
.notice-error { color: var(--coral); }
.log-list { display: grid; max-width: 1120px; padding: 16px 24px 30px; margin: 0 auto; gap: 9px; list-style: none; }
.log-entry {
  position: relative;
  padding: 13px 15px 11px 18px;
  border: 1px solid var(--separator);
  border-radius: 13px;
  background: var(--surface-raise);
  overflow: hidden;
}
.log-entry::before { position: absolute; top: 0; bottom: 0; left: 0; width: 3px; content: ""; background: var(--ink-faint); opacity: .32; }
.log-entry--info::before { background: var(--blue); }
.log-entry--warn {
  border-color: color-mix(in srgb, #d97706 28%, var(--separator));
  background: linear-gradient(100deg, color-mix(in srgb, #f59e0b 7%, var(--surface-raise)), var(--surface-raise) 52%);
}
.log-entry--warn::before { width: 4px; background: #d97706; opacity: 1; }
.log-entry--error {
  border-color: color-mix(in srgb, var(--coral) 38%, var(--separator));
  background: linear-gradient(100deg, color-mix(in srgb, var(--coral) 10%, var(--surface-raise)), var(--surface-raise) 58%);
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--coral) 8%, transparent);
}
.log-entry--error::before { width: 5px; background: var(--coral); opacity: 1; }
.entry-header { display: flex; min-width: 0; align-items: center; gap: 9px; color: var(--ink-faint); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-micro); }
.level-badge { padding: 3px 7px; border-radius: 999px; color: var(--ink-soft); font-family: inherit; font-weight: 800; background: var(--fill); }
.log-entry--info .level-badge { color: var(--blue); background: color-mix(in srgb, var(--blue) 10%, transparent); }
.log-entry--warn .level-badge { color: #b65f00; background: color-mix(in srgb, #f59e0b 13%, transparent); }
.log-entry--error .level-badge { color: var(--coral); background: color-mix(in srgb, var(--coral) 13%, transparent); }
.logger-name { overflow: hidden; color: var(--ink-soft); text-overflow: ellipsis; white-space: nowrap; }
.request-id { margin-left: auto; overflow: hidden; max-width: 190px; text-overflow: ellipsis; white-space: nowrap; }
.entry-message { margin: 10px 0 0; color: var(--ink); font-family: "SF Mono", Menlo, monospace; font-size: 11px; line-height: 1.65; overflow-wrap: anywhere; white-space: pre-wrap; }
.entry-explanation { display: grid; padding: 10px 12px; margin-top: 11px; grid-template-columns: 64px minmax(0, 1fr); align-items: start; gap: 9px; border-radius: 9px; color: var(--ink-soft); background: color-mix(in srgb, #f59e0b 8%, var(--surface)); }
.log-entry--error .entry-explanation { background: color-mix(in srgb, var(--coral) 9%, var(--surface)); }
.entry-explanation strong { color: #a65b00; font-size: var(--font-caption); }
.log-entry--error .entry-explanation strong { color: var(--coral); }
.entry-explanation p { margin: 0; font-size: var(--font-caption); line-height: 1.65; }
.entry-details { margin-top: 9px; }
.entry-details summary { width: fit-content; color: var(--blue); font-size: var(--font-micro); font-weight: 700; cursor: pointer; }
.entry-details pre { max-height: 280px; padding: 11px; margin: 8px 0 0; overflow: auto; border: 1px solid var(--separator); border-radius: 9px; color: var(--ink-soft); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-micro); line-height: 1.55; background: color-mix(in srgb, var(--ink) 4%, var(--surface)); white-space: pre-wrap; overflow-wrap: anywhere; }
.entry-footer { margin-top: 9px; color: var(--ink-faint); font-family: "SF Mono", Menlo, monospace; font-size: var(--font-micro); }
.stream-state { display: grid; min-height: 100%; padding: 40px 24px; place-content: center; justify-items: center; color: var(--ink-faint); text-align: center; }
.stream-state .ui-icon { margin-bottom: 12px; color: var(--blue); }
.stream-state strong { color: var(--ink); font-size: 13px; }
.stream-state p { max-width: 420px; margin: 7px 0 0; font-size: var(--font-caption); line-height: 1.65; }
.stream-state button { min-height: 34px; padding: 0 12px; margin-top: 14px; border: 0; border-radius: 9px; color: var(--blue); font: inherit; font-size: var(--font-caption); font-weight: 700; background: var(--active); cursor: pointer; }
.stream-state--error .ui-icon { color: var(--coral); }
.stream-state--compact { min-height: 240px; }
.state-spinner { width: 24px; height: 24px; margin-bottom: 13px; border: 2px solid color-mix(in srgb, var(--blue) 18%, transparent); border-top-color: var(--blue); border-radius: 50%; animation: log-spin .8s linear infinite; }
@keyframes log-spin { to { transform: rotate(360deg); } }
@keyframes notice-pulse { 50% { opacity: .35; } }

@media (max-width: 760px) {
  .stream-notice { padding: 8px 16px; }
  .notice-error { display: none !important; }
  .log-list { padding: 12px 12px 28px; }
  .log-entry { padding: 12px 12px 10px 16px; }
  .request-id { display: none; }
  .entry-explanation { grid-template-columns: 1fr; gap: 4px; }
}

@media (prefers-reduced-motion: reduce) {
  .state-spinner,
  .stream-notice--loading i { animation: none; }
}
</style>
