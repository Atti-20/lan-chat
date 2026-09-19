<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  shallowRef,
  watch,
} from 'vue'
import { nodeFetch } from '../../../platform/nativeTransport'
import { api } from '../../../services/api'
import type { FileAttachmentData } from '../../../types'
import UiIcon from '../../base/UiIcon.vue'
import PdfPreview from "./PdfPreview.vue";

type PreviewKind =
    | 'video'
    | 'audio'
    | 'pdf'
    | 'text'
    | 'office'
    | 'unsupported'

interface Props {
  open: boolean
  attachment: FileAttachmentData
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  download: []
}>()

const loading = shallowRef(false)
const errorMessage = shallowRef('')
const previewUrl = shallowRef('')
const textContent = shallowRef('')

let objectUrl = ''
let loadSequence = 0

const extension = computed(() => {
  const name = props.attachment.name || ''
  const index = name.lastIndexOf('.')

  return index >= 0
      ? name.slice(index + 1).toLowerCase()
      : ''
})

const previewKind = computed<PreviewKind>(() => {
  const ext = extension.value

  if (['mp4', 'webm', 'mov', 'avi', 'mkv'].includes(ext)) {
    return 'video'
  }

  if (
      ['mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac'].includes(ext)
  ) {
    return 'audio'
  }

  if (ext === 'pdf') {
    return 'pdf'
  }

  if (['txt', 'md', 'csv', 'json', 'log'].includes(ext)) {
    return 'text'
  }

  if (
      [
        'doc',
        'docx',
        'xls',
        'xlsx',
        'ppt',
        'pptx',
      ].includes(ext)
  ) {
    return 'office'
  }

  return 'unsupported'
})

const title = computed(() =>
    props.attachment.name || '文件预览',
)

watch(
    () => [props.open, props.attachment] as const,
    ([open]) => {
      if (open) {
        void loadPreview()
      } else {
        resetPreview()
      }
    },
    { deep: true, immediate: true },
)

async function loadPreview(): Promise<void> {
  resetPreview()
  const sequence = loadSequence
  const kind = previewKind.value

  const source =
      props.attachment.originalUrl
      || props.attachment.url

  if (!source) {
    errorMessage.value = '没有可用的文件地址'
    return
  }

  if (previewKind.value === 'unsupported') {
    errorMessage.value =
        '此文件类型暂不支持在线预览，请下载后查看'
    return
  }

  loading.value = true

  try {
    /*
     * Office 文件最终应由后端转换成 PDF。
     * AVI、MKV 最终应由后端转换成 MP4。
     *
     * 在转换接口完成前，先使用原文件签名 URL。
     */
    const signedUrl =
        await api.files.temporaryUrl(source)

    if (sequence !== loadSequence) return
    if (kind === 'text') {
      await loadTextPreview(signedUrl, sequence)
      return
    }
    if (kind === 'pdf') {
      previewUrl.value = signedUrl
      return
    }
    const response = await nodeFetch(signedUrl)

    if (!response.ok) {
      throw new Error(
          `文件请求失败：HTTP ${response.status}`,
      )
    }

    const blob = await response.blob()
    if (sequence !== loadSequence) return

    if (blob.size === 0) {
      throw new Error('服务器返回了空文件')
    }

    objectUrl = URL.createObjectURL(blob)
    previewUrl.value = objectUrl
  } catch (error) {
    if (sequence !== loadSequence) return
    console.error('[MeshX] 文件预览失败', error)
    errorMessage.value = '文件暂时无法预览'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function loadTextPreview(
    signedUrl: string,
    sequence: number,
): Promise<void> {
  const response = await nodeFetch(signedUrl)

  if (!response.ok) {
    throw new Error(
        `文本请求失败：HTTP ${response.status}`,
    )
  }

  const content = await response.text()
  if (sequence !== loadSequence) return
  const maxCharacters = 1_000_000

  textContent.value =
      content.length > maxCharacters
          ? `${content.slice(
              0,
              maxCharacters,
          )}\n\n……文件内容过长，仅显示前 100 万字符`
          : content
}

function resetPreview(): void {
  loadSequence += 1
  loading.value = false
  errorMessage.value = ''
  previewUrl.value = ''
  textContent.value = ''

  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = ''
  }
}

function close(): void {
  resetPreview()
  emit('close')
}

function handleMediaError(): void {
  /*
   * AVI、MKV 或特殊编码的 MP4 可能无法被当前 WebView 解码。
   */
  errorMessage.value =
      previewKind.value === 'video'
          ? '当前设备不支持该视频编码，请下载后查看'
          : '当前设备不支持该音频编码，请下载后查看'
}

onBeforeUnmount(resetPreview)
</script>

<template>
  <Teleport to="body">
    <div
        v-if="open"
        class="file-preview-backdrop"
        role="presentation"
        @click.self="close"
    >
      <section
          class="file-preview-modal"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
      >
        <header class="preview-header">
          <div>
            <strong>{{ title }}</strong>
            <small>{{ extension.toUpperCase() }}</small>
          </div>

          <button
              type="button"
              aria-label="关闭预览"
              @click="close"
          >
            <UiIcon name="close" :size="20" />
          </button>
        </header>

        <main class="preview-content">
          <div
              v-if="loading"
              class="preview-state"
          >
            正在载入预览…
          </div>

          <div
              v-else-if="errorMessage"
              class="preview-state preview-error"
          >
            {{ errorMessage }}
          </div>

          <video
              v-else-if="
              previewKind === 'video'
              && previewUrl
            "
              class="video-preview"
              :src="previewUrl"
              controls
              playsinline
              preload="metadata"
              @error="handleMediaError"
          />

          <audio
              v-else-if="
              previewKind === 'audio'
              && previewUrl
            "
              class="audio-preview"
              :src="previewUrl"
              controls
              preload="metadata"
              @error="handleMediaError"
          />

          <PdfPreview
              v-else-if="
                previewKind === 'pdf'
                && previewUrl
              "
              :url="previewUrl"
          />

          <div
              v-else-if="previewKind === 'office'"
              class="preview-state"
          >
            Office 文件预览尚未启用，请下载后查看
          </div>
          <pre
              v-else-if="previewKind === 'text'"
              class="text-preview"
          >{{ textContent || '文件内容为空' }}</pre>

          <div
              v-else
              class="preview-state"
          >
            暂无可用预览
          </div>
        </main>

        <footer class="preview-footer">
          <button
              type="button"
              class="download-button"
              @click="emit('download')"
          >
            下载文件
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.file-preview-backdrop {
  --preview-safe-top:
      max(env(safe-area-inset-top, 0px), 24px);
  --preview-safe-right:
      env(safe-area-inset-right, 0px);
  --preview-safe-bottom:
      max(env(safe-area-inset-bottom, 0px), 16px);
  --preview-safe-left:
      env(safe-area-inset-left, 0px);

  position: fixed;
  z-index: 160;
  inset: 0;

  display: grid;
  padding: var(--space-4);
  place-items: center;

  background: rgba(10, 16, 24, 0.68);
  backdrop-filter: blur(14px);
}

.file-preview-modal {
  display: grid;
  width: min(960px, 100%);
  max-height: 90dvh;
  grid-template-rows: auto minmax(0, 1fr) auto;
  overflow: hidden;
  border: 1px solid var(--separator);
  border-radius: var(--radius-lg);
  color: var(--ink);
  background: var(--surface-raise);
  box-shadow: 0 28px 90px rgba(0, 0, 0, .3);
}

.preview-header {
  display: flex;
  min-width: 0;
  padding: 14px 16px;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  border-bottom: 1px solid var(--separator);
}

.preview-header > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.preview-header strong {
  overflow: hidden;
  font-size: var(--font-body);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-header small {
  color: var(--ink-faint);
  font-size: var(--font-micro);
}

.preview-header button {
  display: grid;
  width: var(--control-icon);
  height: var(--control-icon);
  padding: 0;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  font-size: 22px;
  background: var(--fill);
  cursor: pointer;
}

.preview-content {
  display: grid;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  place-items: center;
  background: var(--preview-canvas);
}

.preview-state {
  display: grid;
  min-height: min(260px, 50dvh);
  padding: var(--space-8);
  place-items: center;
  color: var(--ink-faint);
  font-size: var(--font-body-sm);
  text-align: center;
}

.preview-error {
  color: var(--danger);
}

.video-preview {
  width: 100%;
  max-height: 72dvh;
  background: var(--media-canvas);
}

.audio-preview {
  width: min(560px, calc(100% - 32px));
  margin: 40px 16px;
}

.document-preview {
  width: 100%;
  height: 72dvh;
  border: 0;
  background: var(--document-paper);
}

.text-preview {
  width: 100%;
  max-height: 72dvh;
  padding: var(--space-5);
  margin: 0;
  overflow: auto;
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: var(--font-caption);
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.preview-footer {
  display: flex;
  min-height: 62px;
  padding: 12px 16px;
  align-items: center;
  justify-content: flex-end;
  border-top: 1px solid var(--separator);
}

.download-button {
  min-height: 38px;
  padding: 0 16px;
  border: 0;
  border-radius: var(--radius-control);
  color: white;
  font-weight: 600;
  background: var(--action-bg);
  cursor: pointer;
}

@media (max-width: 600px) {
  .file-preview-backdrop {
    padding: 0;
  }

  .file-preview-modal {
    box-sizing: border-box;

    width: 100%;
    height: 100dvh;
    max-height: 100dvh;

    border: 0;
    border-radius: 0;
  }

  /* 顶部标题栏避开状态栏和摄像头挖孔 */
  .preview-header {
    box-sizing: border-box;

    padding-top:
        calc(12px + var(--preview-safe-top));
    padding-right:
        calc(16px + var(--preview-safe-right));
    padding-bottom: 12px;
    padding-left:
        calc(16px + var(--preview-safe-left));
  }

  .preview-header > div {
    min-width: 0;
  }

  .preview-header button {
    flex: 0 0 38px;
    width: 38px;
    height: 38px;
  }

  /* 中间区域必须允许在 Grid 内正确收缩 */
  .preview-content {
    min-width: 0;
    min-height: 0;
    overflow: auto;
  }

  .video-preview {
    display: block;

    width: 100%;
    height: 100%;
    max-height: none;

    object-fit: contain;
    background: var(--media-canvas);
  }

  .document-preview {
    width: 100%;
    height: 100%;
    max-height: none;
  }

  /* 底部操作栏避开手势条和系统导航栏 */
  .preview-footer {
    box-sizing: border-box;

    min-height: auto;

    padding-top: 12px;
    padding-right:
        calc(16px + var(--preview-safe-right));
    padding-bottom:
        calc(12px + var(--preview-safe-bottom));
    padding-left:
        calc(16px + var(--preview-safe-left));
  }

  .download-button {
    min-height: 44px;
  }
}
</style>
