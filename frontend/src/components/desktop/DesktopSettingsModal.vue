<script setup lang="ts">
import { computed, watch } from 'vue'
import { useDesktopSettings } from '../../composables/useDesktopSettings'
import { selectedNode } from '../../platform/nodeContext'
import AppleSwitch from '../base/AppleSwitch.vue'
import UiIcon from '../base/UiIcon.vue'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  switchNode: []
}>()

const settings = useDesktopSettings()
const node = computed(() => selectedNode())
const platformLabel = computed(() => ({
  macos: 'macOS',
  windows: 'Windows',
  linux: 'Linux',
  android: 'Android',
  ios: 'iOS',
  web: 'Web',
  unknown: 'Desktop',
})[settings.runtimeInfo.value?.platform || 'unknown'])

const updateCopy = computed(() => {
  const update = settings.update.value
  if (!update) return '手动检查正式发布渠道'
  if (update.status === 'UP_TO_DATE') return '当前已是最新版本'
  if (update.status === 'AVAILABLE') return `发现新版本 ${update.version || ''}`
  if (update.status === 'UNCONFIGURED') return '当前构建未配置正式更新公钥'
  if (update.status === 'UNSUPPORTED') return '当前平台不支持自动更新'
  return '更新已安装，正在重启'
})

watch(() => props.open, (open) => {
  if (open) void settings.refresh()
}, { immediate: true })
</script>

<template>
  <div
    v-if="open"
    class="modal-backdrop desktop-settings-backdrop apple-modal-backdrop"
    role="presentation"
    @click.self="emit('close')"
  >
    <section
      class="desktop-settings apple-modal-surface"
      role="dialog"
      aria-modal="true"
      aria-labelledby="desktop-settings-title"
    >
      <header>
        <div>
          <p>MeshX Desktop</p>
          <h2 id="desktop-settings-title">桌面端设置</h2>
        </div>
        <button class="apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
          <UiIcon name="close" :size="17" />
        </button>
      </header>

      <div class="desktop-settings-body">
        <section class="runtime-card">
          <UiIcon name="monitor" :size="22" />
          <div>
            <strong>{{ platformLabel }} 客户端</strong>
            <span>v{{ settings.runtimeInfo.value?.version || '—' }}</span>
            <small v-if="node">{{ node.nodeName }} · {{ node.origin }}</small>
          </div>
        </section>

        <div class="setting-row">
          <span>
            <strong>登录时启动 MeshX</strong>
          </span>
          <AppleSwitch
            :model-value="settings.autostartEnabled.value"
            :disabled="settings.loading.value"
            aria-label="登录时启动 MeshX"
            @update:model-value="settings.setAutostart"
          />
        </div>

        <button class="setting-row setting-action apple-list-row" type="button" @click="settings.sendTestNotification">
          <span>
            <strong>系统通知</strong>
          </span>
          <UiIcon name="bell" :size="18" />
        </button>

        <button class="setting-row setting-action apple-list-row" type="button" @click="emit('switchNode')">
          <span>
            <strong>切换局域网节点</strong>
          </span>
          <UiIcon name="arrow-right" :size="18" />
        </button>

        <section class="update-card">
          <div>
            <strong>应用更新</strong>
            <small>{{ updateCopy }}</small>
          </div>
          <button
            v-if="settings.update.value?.status === 'AVAILABLE'"
            type="button"
            :disabled="settings.loading.value"
            @click="settings.checkForUpdate(true)"
          >安装并重启</button>
          <button
            v-else
            type="button"
            :disabled="settings.loading.value"
            @click="settings.checkForUpdate(false)"
          >{{ settings.loading.value ? '检查中…' : '检查更新' }}</button>
        </section>

        <p v-if="settings.error.value" class="settings-error" role="alert">
          {{ settings.error.value }}
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 130;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}
.desktop-settings { width: min(500px, calc(100vw - 28px)); max-height: calc(100dvh - 40px); overflow-y: auto; border: 1px solid var(--glass-border); border-radius: 24px; color: var(--ink); background: var(--surface-raise); box-shadow: 0 28px 80px rgba(0, 0, 0, .22); }
.desktop-settings > header { display: flex; padding: 22px 24px 18px; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--separator); }
.desktop-settings header p { margin: 0 0 4px; color: var(--blue); font-size: 9px; font-weight: 750; letter-spacing: .11em; text-transform: uppercase; }
.desktop-settings h2 { margin: 0; font-size: 20px; }
.desktop-settings header button { display: grid; width: 32px; height: 32px; place-items: center; border: 0; border-radius: 10px; background: var(--fill); cursor: pointer; }
.desktop-settings-body { display: grid; padding: 20px 24px 24px; gap: 10px; }
.runtime-card,
.setting-row,
.update-card { display: flex; min-height: 64px; padding: 13px 14px; align-items: center; gap: 12px; border: 1px solid var(--separator); border-radius: 15px; background: var(--surface); }
.runtime-card > .ui-icon { color: var(--blue); }
.runtime-card div,
.setting-row > span,
.update-card > div { display: grid; min-width: 0; flex: 1; gap: 3px; }
.runtime-card strong,
.setting-row strong,
.update-card strong { font-size: 11px; }
.runtime-card span,
.runtime-card small,
.setting-row small,
.update-card small { overflow: hidden; color: var(--ink-faint); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.setting-action { width: 100%; color: inherit; text-align: left; cursor: pointer; }
.setting-action > .ui-icon { color: var(--blue); }
.update-card button { padding: 7px 10px; border: 0; border-radius: 9px; color: #fff; font-size: 9px; font-weight: 700; background: var(--blue); cursor: pointer; }
.update-card button:disabled { opacity: .5; }
.settings-error { margin: 2px 4px 0; color: var(--coral); font-size: 9px; }

@media (max-width: 480px) {
  .modal-backdrop { padding: 16px; }
}
</style>
