<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useToast } from '../../composables/useToast'
import { useAttachmentDownload } from '../../composables/useAttachmentDownload'
import { currentNodeOrigin } from '../../platform/nodeContext'
import { nativeBridge } from '../../platform/nativeBridge'
import { nodeFetch } from '../../platform/nativeTransport'
import { api } from '../../services/api'
import { loadDirectFile } from '../../services/localChatDb'
import type { FileAttachmentData } from '../../types'
import { formatFileSize } from '../../utils/format'
import UiIcon from '../base/UiIcon.vue'
import FilePreviewModal from "./previews/FilePreviewModal.vue";

interface Props {
  type: 'image' | 'file'
  content?: string
  outgoing?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  outgoing: false,
})
const emit = defineEmits<{
  layoutChange: []
}>()
const toast = useToast()
const attachmentDownload = useAttachmentDownload()
const imageUrl = shallowRef('')
const loading = shallowRef(false)
const previewOpen = shallowRef(false)
const previewUrl = shallowRef('')
const previewThumbnailUrl = shallowRef('')
const previewLoading = shallowRef(false)
const directAvailable = shallowRef<boolean | null>(null)
const filePreviewOpen = shallowRef(false)
let localObjectUrl = ''
let thumbnailObjectUrl = ''
let previewObjectUrl = ''

const data = computed<FileAttachmentData>(() => {
  if (!props.content) return {}
  try {
    return JSON.parse(props.content) as FileAttachmentData
  } catch {
    return { url: props.content, originalUrl: props.content }
  }
})

function restoreOriginalUrl(url: string): string {
  if (!url) return ''
  try {
    const origin = currentNodeOrigin()
    const parsed = new URL(url, origin)
    parsed.pathname = parsed.pathname.replace(
        /\/thumb_([^/]+)$/,
        '/$1',
    )
    if (parsed.origin === origin) {
      return `${parsed.pathname}${parsed.search}${parsed.hash}`
    }
    return parsed.toString()
  } catch {
    return url.replace('/thumb_', '/')
  }
}

const originalImageSource = computed(() => {
  const explicitOriginal = data.value.originalUrl || ''
  if (explicitOriginal) return restoreOriginalUrl(explicitOriginal)
  const fallback = data.value.url || data.value.thumbnailUrl || ''
  return restoreOriginalUrl(fallback)
})

const fileExtension = computed(() => {
  const name = data.value.name || ''
  const separator = name.lastIndexOf('.')
  return separator > -1 ? name.slice(separator + 1).toLowerCase() : ''
})
const filePreviewable = computed(() => (
  fileExtension.value === 'pdf' || ['txt', 'md', 'csv', 'json', 'log'].includes(fileExtension.value)
))
const fileMimeType = computed(() => {
  if (fileExtension.value === 'pdf') return 'application/pdf'
  if (fileExtension.value === 'txt' || fileExtension.value === 'md' || fileExtension.value === 'log') return 'text/plain'
  if (fileExtension.value === 'csv') return 'text/csv'
  if (fileExtension.value === 'json') return 'application/json'
  return 'application/octet-stream'
})

watch(
  () => [props.type, props.content] as const,
  async ([type], _previous, onCleanup) => {
    releaseImageObjectUrls()
    imageUrl.value = ''
    directAvailable.value = null
    const transferId = data.value.transferId
    if (data.value.transferPath === 'PEER_TO_PEER' && transferId) {
      let cancelled = false
      onCleanup(() => { cancelled = true })
      loading.value = true
      try {
        const record = await loadDirectFile(transferId)
        if (cancelled) return
        directAvailable.value = Boolean(record)
        if (record && type === 'image') {
          localObjectUrl = URL.createObjectURL(record.blob)
          imageUrl.value = localObjectUrl
        }
      } finally {
        if (!cancelled) loading.value = false
      }
      return
    }
    if (type !== 'image') return
    const source = data.value.thumbnailUrl || data.value.url
    if (!source) return
    let cancelled = false
    onCleanup(() => { cancelled = true })
    loading.value = true
    try {
      const temporaryUrl = await api.files.temporaryUrl(source)
      if (cancelled) return
      // WKWebView can reject an otherwise valid cross-origin signed image when
      // it is assigned directly to img.src. Fetching the short-lived URL first
      // keeps authorization on the network request and gives the renderer a
      // same-context blob URL. This is intentionally only for the thumbnail;
      // the full-size viewer still streams the original on demand.
      const response = await nodeFetch(temporaryUrl)
      if (!response.ok) throw new Error(`缩略图请求失败：HTTP ${response.status}`)
      const contentType = response.headers.get('Content-Type') || ''
      if (!contentType.startsWith('image/')) throw new Error('缩略图不是图片资源')
      const blob = await response.blob()
      if (cancelled) return
      if (blob.size === 0) throw new Error('缩略图为空')
      thumbnailObjectUrl = URL.createObjectURL(blob)
      imageUrl.value = thumbnailObjectUrl
    } catch {
      if (!cancelled) toast.push('图片暂时无法显示', 'warning')
    } finally {
      if (!cancelled) loading.value = false
    }
  },
  { immediate: true },
)

watch(
    () => [loading.value, imageUrl.value] as const,
    () => {
      requestAnimationFrame(() => {
        emit('layoutChange')
      })
    },
    { flush: 'post'}
)

onBeforeUnmount(releaseImageObjectUrls)

async function download(): Promise<void> {
  // WebRTC 直传文件：从 IndexedDB 读取
  if (data.value.transferPath === 'PEER_TO_PEER' && data.value.transferId) {
    loading.value = true

    try {
      const record = await loadDirectFile(data.value.transferId)
      directAvailable.value = Boolean(record)

      if (!record) {
        toast.push(
            '文件只保存在完成直传的设备上，当前设备没有本地副本',
            'warning',
        )
        return
      }

      const objectUrl = URL.createObjectURL(record.blob)

      triggerDownload(
          objectUrl,
          data.value.name || record.name || 'MeshX 文件',
      )

      window.setTimeout(() => {
        URL.revokeObjectURL(objectUrl)
      }, 1_000)
      toast.push('下载已开始；浏览器将使用默认保存位置', 'success')
    } catch (error) {
      console.error('下载直传文件失败：', error)
      toast.push('文件下载失败', 'danger')
    } finally {
      loading.value = false
    }

    return
  }

  // 服务端中转文件
  const source = data.value.originalUrl || data.value.url

  if (!source) {
    toast.push('没有可用的文件地址', 'warning')
    return
  }

  loading.value = true

  try {
    // 先获取10分钟有效的签名地址
    const signedUrl = await api.files.temporaryUrl(source)

    const downloadUrl = new URL(signedUrl, currentNodeOrigin())
    downloadUrl.searchParams.set('download', 'true')

    if (nativeBridge.runtime() !== 'web') {
      loading.value = false
      const saved = await attachmentDownload.save({
        url: downloadUrl.toString(),
        name: data.value.name || 'MeshX 文件',
        mimeType: fileMimeType.value,
        expectedBytes: data.value.size,
        sha256: data.value.fileHash,
      })
      if (saved) toast.push(`已保存到 ${saved.location}`, 'success')
      return
    }

    // 网页端没有可靠的、可授权的本地保存路径；保持浏览器原生下载并
    // 明确告知用户由浏览器决定保存位置。
    const response = await nodeFetch(downloadUrl.toString())

    if (!response.ok) {
      throw new Error(`文件请求失败：HTTP ${response.status}`)
    }

    const blob = await response.blob()

    if (blob.size === 0) {
      throw new Error('服务器返回了空文件')
    }

    const objectUrl = URL.createObjectURL(blob)

    triggerDownload(
        objectUrl,
        data.value.name || 'MeshX 文件',
    )

    window.setTimeout(() => {
      URL.revokeObjectURL(objectUrl)
    }, 1_000)
    toast.push('下载已开始；浏览器将使用默认保存位置', 'success')
  } catch (error) {
    console.error('文件下载失败：', error)
    toast.push('文件下载失败', 'danger')
  } finally {
    loading.value = false
  }
}

function openFilePreview(): void {
  filePreviewOpen.value = true
}

function closeFilePreview(): void {
  filePreviewOpen.value = false
}

async function openImage(): Promise<void> {
  // WebRTC 设备直传图片直接使用 IndexedDB 生成的 Blob URL
  if (data.value.transferPath === 'PEER_TO_PEER') {
    if (!imageUrl.value) {
      toast.push(
          '图片只保存在完成直传的设备上，当前设备没有本地副本',
          'warning',
      )
      return
    }

    previewOpen.value = true
    previewLoading.value = false
    previewThumbnailUrl.value = ''
    previewUrl.value = imageUrl.value
    return
  }

  const source = originalImageSource.value

  if (!source) {
    toast.push('没有可用的原图地址', 'warning')
    return
  }

  previewOpen.value = true
  previewLoading.value = true
  previewUrl.value = ''
  previewThumbnailUrl.value = imageUrl.value

  try {
    const temporaryUrl = await api.files.temporaryUrl(source)

    // Android WebView、Tauri 和网页统一先获取文件，
    // 再通过 Blob URL 显示，避免跨域、CORP 和混合内容限制。
    const response = await nodeFetch(temporaryUrl)

    if (!response.ok) {
      throw new Error(`原图请求失败：HTTP ${response.status}`)
    }

    const contentType =
        response.headers.get('Content-Type') || ''

    if (!contentType.startsWith('image/')) {
      throw new Error(
          `返回内容不是图片：${contentType || '未知类型'}`,
      )
    }

    const blob = await response.blob()

    if (blob.size === 0) {
      throw new Error('服务器返回了空图片')
    }

    // 防止重复打开时残留旧 Blob URL
    if (previewObjectUrl) {
      URL.revokeObjectURL(previewObjectUrl)
    }

    previewObjectUrl = URL.createObjectURL(blob)
    previewUrl.value = previewObjectUrl
  } catch (error) {
    console.error('打开原图失败：', error)

    previewOpen.value = false
    previewLoading.value = false
    previewUrl.value = ''
    previewThumbnailUrl.value = ''

    toast.push('原图暂时无法打开', 'danger')
  }
}
function triggerDownload(url: string, name: string): void {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.hidden = true
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
}

function releaseImageObjectUrls(): void {
  if (localObjectUrl) URL.revokeObjectURL(localObjectUrl)
  if (thumbnailObjectUrl) URL.revokeObjectURL(thumbnailObjectUrl)
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl)
  localObjectUrl = ''
  thumbnailObjectUrl = ''
  previewObjectUrl = ''
}

function closePreview(): void {
  previewOpen.value = false
  previewLoading.value = false
  previewThumbnailUrl.value = ''
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl)
  previewObjectUrl = ''
  previewUrl.value = ''
}

function notifyLayoutChange(): void {
  requestAnimationFrame(() => {
    emit('layoutChange')
  })
}

function handleThumbnailError(): void {
  imageUrl.value = ''
  notifyLayoutChange()
  toast.push('图片缩略图暂时无法显示', 'warning')
}

function handlePreviewLoaded(): void {
  previewLoading.value = false
}

function handlePreviewError(): void {
  previewOpen.value = false
  previewLoading.value = false
  previewThumbnailUrl.value = ''

  if (previewObjectUrl) {
    URL.revokeObjectURL(previewObjectUrl)
  }

  previewObjectUrl = ''
  previewUrl.value = ''

  toast.push('原图暂时无法打开', 'danger')
}
</script>

<template>
  <button v-if="type === 'image'" class="image-attachment" type="button" :disabled="loading" @click="openImage">
    <span v-if="loading" class="image-loading">正在载入图片…</span>
    <img v-else-if="imageUrl" :src="imageUrl" alt="聊天图片，点击查看原图" @load="notifyLayoutChange" @error="handleThumbnailError"/>
    <span v-else class="image-loading">{{ directAvailable === false ? '当前设备没有直传副本' : '图片不可用' }}</span>
    <span v-if="data.thumbnailUrl" class="image-hint">查看原图</span>
    <span v-else-if="data.transferPath" class="path-hint">{{ data.transferPath === 'PEER_TO_PEER' ? '设备直传' : '节点中转' }}</span>
  </button>

  <button
    v-else
    class="file-attachment"
    :class="{ 'file-attachment--outgoing': outgoing }"
    type="button"
    :disabled="loading || attachmentDownload.saving.value"
    :aria-label="`查看 ${data.name || '文件'}`"
    @click="openFilePreview"
  >
    <span class="file-icon" aria-hidden="true">
      <UiIcon name="file" :size="20" />
    </span>
    <span class="file-copy">
      <strong>{{ data.name || '文件' }}</strong>
      <small>{{ attachmentDownload.saving.value
        ? attachmentDownload.percent.value === null
          ? `已保存 ${formatFileSize(attachmentDownload.writtenBytes.value)}`
          : `正在保存 ${attachmentDownload.percent.value}%`
        : loading ? '正在准备…' : `${formatFileSize(data.size)} · ${data.transferPath === 'PEER_TO_PEER' ? '设备直传' : '节点中转'}` }}</small>
    </span>
    <span class="download-action" aria-hidden="true">
      <UiIcon class="download-icon" name="download" :size="18" />
    </span>
  </button>

  <Teleport to="body">
    <div v-if="previewOpen" class="image-preview-backdrop" role="presentation" @click.self="closePreview">
      <section class="image-preview" role="dialog" aria-modal="true" aria-label="原图预览">
        <button class="preview-close" type="button" aria-label="关闭原图" @click="closePreview"><UiIcon name="close" :size="20" /></button>
        <img
          v-if="previewLoading && previewThumbnailUrl"
          class="preview-thumbnail"
          :src="previewThumbnailUrl"
          alt=""
        />
        <span v-if="previewLoading && previewThumbnailUrl" class="preview-loading">正在载入原图…</span>
        <span v-else-if="previewLoading" class="preview-placeholder">正在载入原图…</span>
        <img
          v-if="previewUrl"
          :src="previewUrl"
          :class="{ 'preview-image--loading': previewLoading }"
          alt="聊天原图"
          @load="handlePreviewLoaded"
          @error="handlePreviewError"
        />
      </section>
    </div>
  </Teleport>
  <FilePreviewModal
      :open="filePreviewOpen"
      :attachment="data"
      @close="closeFilePreview"
      @download="download"
  />
</template>

<style scoped>
.image-attachment { position: relative; display: block; max-width: min(340px, 62vw); min-width: 120px; min-height: 90px; padding: 0; overflow: hidden; border: 0; color: var(--ink-soft); cursor: zoom-in; }
.image-attachment img { display: block; width: auto; max-width: 100%; max-height: 330px; object-fit: cover; }
@media (max-width: 600px) {
  .image-attachment {max-width: min(240px, 52vw); min-width: 100px; min-height: 80px; border-radius: 14px 14px 14px 8px;}
  .image-attachment img {max-height: 220px;}
}
.image-loading { display: grid; min-height: 150px; padding: var(--space-5); place-items: center; font-size: var(--font-caption); }
.image-hint { position: absolute; right: 9px; bottom: 9px; padding: 5px 8px; border: 1px solid rgba(255,255,255,.35); border-radius: var(--radius-sm); color: white; font-size: var(--font-micro); font-weight: 700; background: rgba(16,35,63,.48); backdrop-filter: blur(10px); }
.path-hint { position: absolute; right: 9px; bottom: 9px; padding: 5px 8px; border-radius: var(--radius-sm); color: white; font-size: var(--font-micro); font-weight: 700; background: rgba(16,35,63,.48); backdrop-filter: blur(10px); }
.file-attachment { display: flex; width: min(288px, 68vw); min-height: 60px; padding: 9px 10px; align-items: center; gap: 10px; border: 0; border-radius: 15px; color: inherit; text-align: left; background: transparent; cursor: pointer; transition: background-color 150ms ease, transform 150ms ease; }
.file-attachment:hover { background: rgba(0,122,255,.045); }
.file-attachment:active { transform: scale(.985); }
.file-attachment:disabled { cursor: wait; opacity: .72; }
.file-icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: var(--radius-control); color: var(--accent-text); background: rgba(0,122,255,.09); }
.file-icon .ui-icon { width: 20px; }
.file-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.file-copy strong { overflow: hidden; color: currentColor; font-size: var(--font-body-sm); font-weight: 600; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.file-copy small { color: var(--ink-faint); font-size: var(--font-micro); line-height: 1.2; }
.download-action { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-items: center; border-radius: var(--radius-control); color: var(--accent-text); background: rgba(0,122,255,.09); transition: background-color 150ms ease; }
.download-icon { width: 18px; }
.file-attachment:hover .download-action { background: rgba(0,122,255,.14); }
.file-attachment--outgoing:hover { background: rgba(255,255,255,.08); }
.file-attachment--outgoing .file-icon,
.file-attachment--outgoing .download-action { color: white; background: rgba(255,255,255,.16); }
.file-attachment--outgoing .file-copy small { color: rgba(255,255,255,.72); }
.file-attachment--outgoing:hover .download-action { background: rgba(255,255,255,.24); }
.image-preview-backdrop { position: fixed; z-index: 150; inset: 0; display: grid; padding: 28px; place-items: center; background: rgba(12,18,26,.72); backdrop-filter: blur(16px) saturate(130%); -webkit-backdrop-filter: blur(16px) saturate(130%); }
.image-preview { position: relative; display: grid; max-width: min(94vw, 1440px); max-height: 92dvh; place-items: center; }
.image-preview img { display: block; max-width: 100%; max-height: 92dvh; object-fit: contain; border-radius: var(--radius-md); box-shadow: 0 28px 90px rgba(0,0,0,.36); }
.image-preview .preview-thumbnail { max-width: min(82vw, 960px); max-height: 82dvh; opacity: .72; filter: blur(0.4px) saturate(.92); }
.preview-image--loading { position: absolute; opacity: 0; pointer-events: none; }
.preview-close { position: absolute; z-index: 1; top: 12px; right: 12px; display: grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid rgba(255,255,255,.24); border-radius: 50%; color: white; font-size: var(--font-page-title); background: rgba(18,24,32,.58); cursor: pointer; backdrop-filter: blur(10px); }
.preview-loading { position: absolute; z-index: 1; bottom: 14px; left: 50%; padding: 7px 11px; border: 1px solid rgba(255,255,255,.2); border-radius: var(--radius-pill); color: white; font-size: var(--font-micro); background: rgba(18,24,32,.62); transform: translateX(-50%); backdrop-filter: blur(10px); }
.preview-placeholder { display: grid; min-width: min(80vw, 520px); min-height: 280px; place-items: center; border-radius: 18px; color: white; font-size: var(--font-caption); background: rgba(255,255,255,.08); }
.file-preview-backdrop { position: fixed; z-index: 150; inset: 0; display: grid; padding: var(--space-5); place-items: center; background: rgba(12,18,26,.52); backdrop-filter: blur(18px) saturate(125%); -webkit-backdrop-filter: blur(18px) saturate(125%); }
.file-preview-header { display: grid; min-width: 0; padding: 16px 18px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 11px; border-bottom: 1px solid var(--separator); }
.file-preview-header .file-icon { width: 36px; height: 36px; border-radius: var(--radius-control); }
.file-preview-header div { display: grid; min-width: 0; gap: 2px; }
.file-preview-header strong { overflow: hidden; font-size: var(--font-body); text-overflow: ellipsis; white-space: nowrap; }
.file-preview-header small { color: var(--ink-faint); font-size: var(--font-micro); }
.file-preview-header button { display: grid; width: 32px; height: 32px; padding: 0; place-items: center; border: 0; border-radius: 50%; color: var(--ink-soft); font-size: 22px; background: var(--fill); cursor: pointer; }
.file-preview-frame { width: 100%; min-height: min(58dvh, 540px); border: 0; background: white; }
.file-preview-state { display: grid; min-height: 220px; max-width: 430px; padding: var(--space-8); margin: 0 auto; place-items: center; color: var(--ink-faint); font-size: var(--font-body-sm); line-height: 1.6; text-align: center; }
.file-preview footer { display: flex; min-height: 68px; padding: 14px 18px; align-items: center; justify-content: flex-end; gap: 10px; border-top: 1px solid var(--separator); }
.download-progress { position: relative; height: 4px; min-width: 120px; flex: 1; overflow: hidden; border-radius: var(--radius-pill); background: var(--fill); }
.download-progress span { display: block; height: 100%; border-radius: inherit; background: var(--blue); transition: width 120ms linear; }
.download-progress--indeterminate span { animation: download-indeterminate 1.2s ease-in-out infinite alternate; }
@keyframes download-indeterminate { from { transform: translateX(-40%); } to { transform: translateX(225%); } }
.file-cancel-button { min-height: 38px; padding: 0 12px; border: 1px solid var(--separator); border-radius: var(--radius-control); color: var(--ink-soft); font: inherit; font-size: var(--font-caption); font-weight: 650; background: var(--surface); cursor: pointer; }
.file-save-button { display: inline-flex; min-height: 38px; padding: 0 14px; align-items: center; gap: 7px; border: 0; border-radius: var(--radius-control); color: white; font-size: var(--font-caption); font-weight: 700; background: var(--action-bg); cursor: pointer; }
.file-save-button:disabled { cursor: wait; opacity: .7; }

.image-attachment { border-radius: 15px 15px 15px 5px; background: var(--fill); }
@media (max-width: 760px) { .file-preview-backdrop { padding: var(--space-3); align-items: end; } .file-preview { width: 100%; max-height: 88dvh; border-radius: var(--radius-lg); } .file-preview-frame { min-height: 50dvh; } }
</style>
