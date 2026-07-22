<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useToast } from '../../composables/useToast'
import { currentNodeOrigin } from '../../platform/nodeContext'
import { nativeBridge } from '../../platform/nativeBridge'
import { api } from '../../services/api'
import { loadDirectFile } from '../../services/localChatDb'
import type { FileAttachmentData } from '../../types'
import { formatFileSize } from '../../utils/format'
import UiIcon from '../base/UiIcon.vue'

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
const imageUrl = shallowRef('')
const loading = shallowRef(false)
const previewOpen = shallowRef(false)
const previewUrl = shallowRef('')
const previewThumbnailUrl = shallowRef('')
const previewLoading = shallowRef(false)
const fileActionsOpen = shallowRef(false)
const filePreviewLoading = shallowRef(false)
const filePreviewUrl = shallowRef('')
const directAvailable = shallowRef<boolean | null>(null)
let localObjectUrl = ''
let thumbnailObjectUrl = ''

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
      const response = await fetch(temporaryUrl)
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
      const saved = await nativeBridge.saveFile(
        downloadUrl.toString(),
        data.value.name || 'MeshX 文件',
        fileMimeType.value,
      )
      if (saved) toast.push(`已保存到 ${saved.location}`, 'success')
      return
    }

    // 网页端没有可靠的、可授权的本地保存路径；保持浏览器原生下载并
    // 明确告知用户由浏览器决定保存位置。
    const response = await fetch(downloadUrl.toString())

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

async function openFileActions(): Promise<void> {
  fileActionsOpen.value = true
  filePreviewUrl.value = ''
  if (!filePreviewable.value || data.value.transferPath === 'PEER_TO_PEER') return
  const source = data.value.originalUrl || data.value.url
  if (!source) return
  filePreviewLoading.value = true
  try {
    filePreviewUrl.value = await api.files.temporaryUrl(source)
  } catch {
    toast.push('文件预览暂时不可用', 'warning')
  } finally {
    filePreviewLoading.value = false
  }
}

function closeFileActions(): void {
  fileActionsOpen.value = false
  filePreviewUrl.value = ''
  filePreviewLoading.value = false
}

async function openImage(): Promise<void> {
  if (data.value.transferPath === 'PEER_TO_PEER') {
    if (!imageUrl.value) {
      toast.push('图片只保存在完成直传的设备上，当前设备没有本地副本', 'warning')
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
    previewUrl.value = await api.files.temporaryUrl(source)
  } catch {
    previewOpen.value = false
    previewLoading.value = false
    previewUrl.value = ''
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
  localObjectUrl = ''
  thumbnailObjectUrl = ''
}

function closePreview(): void {
  previewOpen.value = false
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
  previewUrl.value = ''
  previewLoading.value = false
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
    :disabled="loading"
    :aria-label="`查看 ${data.name || '文件'}`"
    @click="openFileActions"
  >
    <span class="file-icon" aria-hidden="true">
      <UiIcon name="file" :size="20" />
    </span>
    <span class="file-copy">
      <strong>{{ data.name || '文件' }}</strong>
      <small>{{ loading ? '正在保存…' : `${formatFileSize(data.size)} · ${data.transferPath === 'PEER_TO_PEER' ? '设备直传' : '节点中转'}` }}</small>
    </span>
    <span class="download-action" aria-hidden="true">
      <UiIcon class="download-icon" name="download" :size="18" />
    </span>
  </button>

  <Teleport to="body">
    <div v-if="fileActionsOpen" class="file-preview-backdrop" role="presentation" @click.self="closeFileActions">
      <section class="file-preview" role="dialog" aria-modal="true" :aria-label="`${data.name || '文件'}操作`">
        <header class="file-preview-header">
          <span class="file-icon" aria-hidden="true"><UiIcon name="file" :size="20" /></span>
          <div><strong>{{ data.name || '文件' }}</strong><small>{{ formatFileSize(data.size) }}</small></div>
          <button type="button" aria-label="关闭" @click="closeFileActions">×</button>
        </header>
        <div v-if="filePreviewLoading" class="file-preview-state">正在载入预览…</div>
        <iframe v-else-if="filePreviewUrl" class="file-preview-frame" :src="filePreviewUrl" :title="`${data.name || '文件'}预览`" />
        <p v-else class="file-preview-state">
          {{ data.transferPath === 'PEER_TO_PEER'
            ? '直传文件仅保存在参与传输的设备上，请下载后查看。'
            : filePreviewable ? '暂时无法预览该文件。' : '此文件类型暂不支持直接预览。' }}
        </p>
        <footer><button class="file-save-button" type="button" :disabled="loading" @click="download"><UiIcon name="download" :size="17" />{{ loading ? '正在保存…' : '下载并选择保存位置' }}</button></footer>
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="previewOpen" class="image-preview-backdrop" role="presentation" @click.self="closePreview">
      <section class="image-preview" role="dialog" aria-modal="true" aria-label="原图预览">
        <button class="preview-close" type="button" aria-label="关闭原图" @click="closePreview">×</button>
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
</template>

<style scoped>
.image-attachment { position: relative; display: block; max-width: min(340px, 62vw); min-width: 160px; min-height: 120px; padding: 0; overflow: hidden; border: 0; border-radius: 18px 18px 18px 8px; color: var(--ink-soft); background: rgba(223,236,248,.72); cursor: zoom-in; }
.image-attachment img { display: block; width: 100%; max-height: 330px; object-fit: cover; }
.image-loading { display: grid; min-height: 150px; padding: 20px; place-items: center; font-size: 12px; }
.image-hint { position: absolute; right: 9px; bottom: 9px; padding: 5px 8px; border: 1px solid rgba(255,255,255,.35); border-radius: 9px; color: white; font-size: 9px; font-weight: 700; background: rgba(16,35,63,.48); backdrop-filter: blur(10px); }
.path-hint { position: absolute; right: 9px; bottom: 9px; padding: 5px 8px; border-radius: 9px; color: white; font-size: 9px; font-weight: 700; background: rgba(16,35,63,.48); backdrop-filter: blur(10px); }
.file-attachment { display: flex; width: min(288px, 68vw); min-height: 60px; padding: 9px 10px; align-items: center; gap: 10px; border: 0; border-radius: 15px; color: inherit; text-align: left; background: transparent; cursor: pointer; transition: background-color 150ms ease, transform 150ms ease; }
.file-attachment:hover { background: rgba(0,122,255,.045); }
.file-attachment:active { transform: scale(.985); }
.file-attachment:disabled { cursor: wait; opacity: .72; }
.file-icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 12px; color: var(--blue); background: rgba(0,122,255,.09); }
.file-icon .ui-icon { width: 20px; }
.file-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.file-copy strong { overflow: hidden; color: currentColor; font-size: 13px; font-weight: 600; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.file-copy small { color: var(--ink-faint); font-size: 10px; line-height: 1.2; }
.download-action { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-items: center; border-radius: 10px; color: var(--blue); background: rgba(0,122,255,.09); transition: background-color 150ms ease; }
.download-icon { width: 18px; }
.file-attachment:hover .download-action { background: rgba(0,122,255,.14); }
.file-attachment--outgoing:hover { background: rgba(255,255,255,.08); }
.file-attachment--outgoing .file-icon,
.file-attachment--outgoing .download-action { color: white; background: rgba(255,255,255,.16); }
.file-attachment--outgoing .file-copy small { color: rgba(255,255,255,.72); }
.file-attachment--outgoing:hover .download-action { background: rgba(255,255,255,.24); }
.image-preview-backdrop { position: fixed; z-index: 150; inset: 0; display: grid; padding: 28px; place-items: center; background: rgba(12,18,26,.72); backdrop-filter: blur(16px) saturate(130%); -webkit-backdrop-filter: blur(16px) saturate(130%); }
.image-preview { position: relative; display: grid; max-width: min(94vw, 1440px); max-height: 92dvh; place-items: center; }
.image-preview img { display: block; max-width: 100%; max-height: 92dvh; object-fit: contain; border-radius: 14px; box-shadow: 0 28px 90px rgba(0,0,0,.36); }
.image-preview .preview-thumbnail { max-width: min(82vw, 960px); max-height: 82dvh; opacity: .72; filter: blur(0.4px) saturate(.92); }
.preview-image--loading { position: absolute; opacity: 0; pointer-events: none; }
.preview-close { position: absolute; z-index: 1; top: 12px; right: 12px; display: grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid rgba(255,255,255,.24); border-radius: 50%; color: white; font-size: 24px; background: rgba(18,24,32,.58); cursor: pointer; backdrop-filter: blur(10px); }
.preview-loading { position: absolute; z-index: 1; bottom: 14px; left: 50%; padding: 7px 11px; border: 1px solid rgba(255,255,255,.2); border-radius: 999px; color: white; font-size: 11px; background: rgba(18,24,32,.62); transform: translateX(-50%); backdrop-filter: blur(10px); }
.preview-placeholder { display: grid; min-width: min(80vw, 520px); min-height: 280px; place-items: center; border-radius: 18px; color: white; font-size: 12px; background: rgba(255,255,255,.08); }
.file-preview-backdrop { position: fixed; z-index: 150; inset: 0; display: grid; padding: 20px; place-items: center; background: rgba(12,18,26,.52); backdrop-filter: blur(18px) saturate(125%); -webkit-backdrop-filter: blur(18px) saturate(125%); }
.file-preview { display: grid; width: min(100%, 760px); max-height: min(82dvh, 760px); overflow: hidden; border: 1px solid var(--separator); border-radius: 22px; color: var(--ink); background: var(--surface-raised, var(--surface)); box-shadow: 0 24px 72px rgba(0,0,0,.25), inset 0 1px 0 var(--highlight-soft); }
.file-preview-header { display: grid; min-width: 0; padding: 16px 18px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 11px; border-bottom: 1px solid var(--separator); }
.file-preview-header .file-icon { width: 36px; height: 36px; border-radius: 11px; }
.file-preview-header div { display: grid; min-width: 0; gap: 2px; }
.file-preview-header strong { overflow: hidden; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.file-preview-header small { color: var(--ink-faint); font-size: 11px; }
.file-preview-header button { display: grid; width: 32px; height: 32px; padding: 0; place-items: center; border: 0; border-radius: 50%; color: var(--ink-soft); font-size: 22px; background: var(--fill); cursor: pointer; }
.file-preview-frame { width: 100%; min-height: min(58dvh, 540px); border: 0; background: white; }
.file-preview-state { display: grid; min-height: 220px; max-width: 430px; padding: 32px; margin: 0 auto; place-items: center; color: var(--ink-faint); font-size: 13px; line-height: 1.6; text-align: center; }
.file-preview footer { display: flex; padding: 14px 18px; justify-content: flex-end; border-top: 1px solid var(--separator); }
.file-save-button { display: inline-flex; min-height: 38px; padding: 0 14px; align-items: center; gap: 7px; border: 0; border-radius: 10px; color: white; font-size: 12px; font-weight: 700; background: var(--blue); cursor: pointer; }
.file-save-button:disabled { cursor: wait; opacity: .7; }

.image-attachment { border-radius: 15px 15px 15px 5px; background: var(--fill); }
@media (max-width: 760px) { .file-preview-backdrop { padding: 12px; align-items: end; } .file-preview { width: 100%; max-height: 88dvh; border-radius: 20px; } .file-preview-frame { min-height: 50dvh; } }
</style>
