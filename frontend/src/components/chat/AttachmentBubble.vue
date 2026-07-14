<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useToast } from '../../composables/useToast'
import { api } from '../../services/api'
import { formatFileSize } from '../../utils/format'

interface Props {
  type: 'image' | 'file'
  content?: string
  outgoing?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  outgoing: false,
})
const toast = useToast()
const imageUrl = shallowRef('')
const loading = shallowRef(false)
const previewOpen = shallowRef(false)
const previewUrl = shallowRef('')
const previewThumbnailUrl = shallowRef('')
const previewLoading = shallowRef(false)

interface AttachmentData {
  url?: string
  originalUrl?: string
  thumbnailUrl?: string
  name?: string
  size?: number
}

const data = computed<AttachmentData>(() => {
  if (!props.content) return {}
  try {
    return JSON.parse(props.content) as AttachmentData
  } catch {
    return { url: props.content, originalUrl: props.content }
  }
})

watch(
  () => [props.type, props.content] as const,
  async ([type], _previous, onCleanup) => {
    if (type !== 'image') return
    const source = data.value.thumbnailUrl || data.value.url
    if (!source) return
    let cancelled = false
    onCleanup(() => { cancelled = true })
    loading.value = true
    try {
      const temporaryUrl = await api.files.temporaryUrl(source)
      if (cancelled) return
      imageUrl.value = temporaryUrl
    } catch {
      if (!cancelled) toast.push('图片暂时无法显示', 'warning')
    } finally {
      if (!cancelled) loading.value = false
    }
  },
  { immediate: true },
)

async function download(): Promise<void> {
  const source = data.value.originalUrl || data.value.url
  if (!source) return
  loading.value = true
  try {
    const url = await api.files.temporaryUrl(source)
    const anchor = document.createElement('a')
    anchor.href = `${url}?download=true`
    anchor.download = data.value.name || 'LanChat 文件'
    anchor.hidden = true
    document.body.append(anchor)
    anchor.click()
    anchor.remove()
  } catch {
    toast.push('文件下载失败', 'danger')
  } finally {
    loading.value = false
  }
}

async function openImage(): Promise<void> {
  const source = data.value.originalUrl || data.value.url
  if (!source) return
  previewOpen.value = true
  previewLoading.value = true
  previewUrl.value = ''
  previewThumbnailUrl.value = imageUrl.value
  try {
    const thumbnailSource = data.value.thumbnailUrl || data.value.url
    previewUrl.value = source === thumbnailSource && imageUrl.value
      ? imageUrl.value
      : await api.files.temporaryUrl(source)
  } catch {
    previewOpen.value = false
    previewLoading.value = false
    toast.push('原图暂时无法打开', 'danger')
  }
}

function closePreview(): void {
  previewOpen.value = false
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
    <img v-else-if="imageUrl" :src="imageUrl" alt="聊天图片，点击查看原图" />
    <span v-else class="image-loading">图片不可用</span>
    <span v-if="data.thumbnailUrl" class="image-hint">查看原图</span>
  </button>

  <button
    v-else
    class="file-attachment"
    :class="{ 'file-attachment--outgoing': outgoing }"
    type="button"
    :disabled="loading"
    :aria-label="`下载 ${data.name || '文件'}`"
    @click="download"
  >
    <span class="file-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M14 2v6h6M10 13h4M10 17h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
    </span>
    <span class="file-copy">
      <strong>{{ data.name || '文件' }}</strong>
      <small>{{ loading ? '正在准备下载…' : formatFileSize(data.size) }}</small>
    </span>
    <span class="download-action" aria-hidden="true">
      <svg class="download-icon" viewBox="0 0 24 24" fill="none"><path d="M12 5v11m0 0-4-4m4 4 4-4M6 20h12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
    </span>
  </button>

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
.file-attachment { display: flex; width: min(288px, 68vw); min-height: 60px; padding: 9px 10px; align-items: center; gap: 10px; border: 0; border-radius: 15px; color: inherit; text-align: left; background: transparent; cursor: pointer; transition: background-color 150ms ease, transform 150ms ease; }
.file-attachment:hover { background: rgba(0,122,255,.045); }
.file-attachment:active { transform: scale(.985); }
.file-attachment:disabled { cursor: wait; opacity: .72; }
.file-icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 12px; color: var(--blue); background: rgba(0,122,255,.09); }
.file-icon svg { width: 20px; }
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
.image-preview-backdrop { position: fixed; z-index: 150; inset: 0; display: grid; padding: 28px; place-items: center; background: rgba(12,18,26,.72); backdrop-filter: blur(12px); }
.image-preview { position: relative; display: grid; max-width: min(94vw, 1440px); max-height: 92dvh; place-items: center; }
.image-preview img { display: block; max-width: 100%; max-height: 92dvh; object-fit: contain; border-radius: 14px; box-shadow: 0 28px 90px rgba(0,0,0,.36); }
.image-preview .preview-thumbnail { opacity: .82; filter: saturate(.92); }
.preview-image--loading { position: absolute; opacity: 0; pointer-events: none; }
.preview-close { position: absolute; z-index: 1; top: 12px; right: 12px; display: grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid rgba(255,255,255,.24); border-radius: 50%; color: white; font-size: 24px; background: rgba(18,24,32,.58); cursor: pointer; backdrop-filter: blur(10px); }
.preview-loading { position: absolute; z-index: 1; bottom: 14px; left: 50%; padding: 7px 11px; border: 1px solid rgba(255,255,255,.2); border-radius: 999px; color: white; font-size: 11px; background: rgba(18,24,32,.62); transform: translateX(-50%); backdrop-filter: blur(10px); }
.preview-placeholder { display: grid; min-width: min(80vw, 520px); min-height: 280px; place-items: center; border-radius: 18px; color: white; font-size: 12px; background: rgba(255,255,255,.08); }

.image-attachment { border-radius: 15px 15px 15px 5px; background: var(--fill); }
</style>
