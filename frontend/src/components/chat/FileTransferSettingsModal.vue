<script setup lang="ts">
import { useFileTransferSettings } from '../../composables/useFileTransferSettings'
import AppleSwitch from '../base/AppleSwitch.vue'
import UiIcon from '../base/UiIcon.vue'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const settings = useFileTransferSettings()

function updatePreference(enabled: boolean): void {
  settings.setPreferDirectFileTransfer(enabled)
}
</script>

<template>
  <div
    v-if="props.open"
    class="modal-backdrop apple-modal-backdrop"
    role="presentation"
    @click.self="emit('close')"
  >
    <section
      class="file-transfer-sheet apple-modal-surface"
      role="dialog"
      aria-modal="true"
      aria-labelledby="file-transfer-settings-title"
    >
      <button class="close-button apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
        <UiIcon name="close" :size="16" />
      </button>

      <h2 id="file-transfer-settings-title">文件传输方式</h2>

      <div class="file-transfer-list">
        <section class="file-transfer-option">
          <UiIcon name="file" :size="22" />
          <div>
            <strong>节点中转（默认）</strong>
            <small>文件保存到受权限保护的节点，可在其他已登录设备上下载。</small>
          </div>
        </section>

        <div class="setting-row">
          <span>
            <strong>优先尝试局域网设备直传</strong>
            <small>仅限私聊且双方在线；协商最多等待 2 秒，失败会立即改用节点中转。</small>
          </span>
          <AppleSwitch
            :model-value="settings.preferDirectFileTransfer.value"
            aria-label="优先尝试局域网设备直传"
            @update:model-value="updatePreference"
          />
        </div>

        <p class="setting-note">
          直传文件只保存在完成传输的两台设备上，不适合需要长期留存或多设备访问的文件。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 110;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.file-transfer-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  max-height: calc(100dvh - 40px);
  padding: 28px 24px 22px;
  gap: 2px;
  border-radius: 22px;
  background: var(--surface-raise);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow-y: auto;
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.close-button {
  position: absolute;
  top: 14px;
  right: 14px;
  display: grid;
  width: 34px;
  height: 34px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
}
.close-button:hover { background: var(--button-hover); }
.close-button .ui-icon { width: 16px; }

.file-transfer-sheet h2 { margin: 0; font-size: 20px; letter-spacing: -0.02em; }
.file-transfer-list { display: grid; margin-top: 14px; gap: 4px; }
.file-transfer-option,
.setting-row { display: flex; padding: 12px; align-items: center; gap: 12px; border-radius: 13px; background: var(--fill); }
.file-transfer-option > .ui-icon { color: var(--blue); flex-shrink: 0; }
.file-transfer-option > div,
.setting-row > span { display: grid; min-width: 0; flex: 1; gap: 3px; }
.file-transfer-option strong,
.setting-row strong { font-size: 11px; }
.file-transfer-option small,
.setting-row small { color: var(--ink-faint); font-size: 9px; line-height: 1.45; }
.setting-note { margin: 10px 4px 0; color: var(--ink-faint); font-size: 10px; line-height: 1.5; }

@media (max-width: 480px) {
  .modal-backdrop { padding: 16px; }
}
</style>
