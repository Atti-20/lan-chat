<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useToast } from '../../composables/useToast'
import { api } from '../../services/api'
import { formatFileSize } from '../../utils/format'

interface Props {
  type: 'image' | 'file'
  content?: string
}

const props = defineProps<Props>()
const toast = useToast()
const imageUrl = shallowRef('')
const loading = shallowRef(false)

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
      const blob = await api.files.blob(source)
      if (cancelled) return
      revokeImageUrl()
      imageUrl.value = URL.createObjectURL(blob)
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
    const blob = await api.files.blob(source)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = data.value.name || 'LanChat 文件'
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    toast.push('文件下载失败', 'danger')
  } finally {
    loading.value = false
  }
}

async function openImage(): Promise<void> {
  const source = data.value.originalUrl || data.value.url
  if (!source) return
  try {
    const blob = await api.files.blob(source)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch {
    toast.push('原图暂时无法打开', 'danger')
  }
}

function revokeImageUrl(): void {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}

onBeforeUnmount(revokeImageUrl)
</script>

<template>
  <button v-if="type === 'image'" class="image-attachment" type="button" :disabled="loading" @click="openImage">
    <span v-if="loading" class="image-loading">正在载入图片…</span>
    <img v-else-if="imageUrl" :src="imageUrl" alt="聊天图片，点击查看原图" />
    <span v-else class="image-loading">图片不可用</span>
    <span v-if="data.thumbnailUrl" class="image-hint">查看原图</span>
  </button>

  <button v-else class="file-attachment" type="button" :disabled="loading" @click="download">
    <span class="file-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none"><path d="M7 3h7l4 4v14H7V3Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M14 3v5h5M10 13h5m-5 4h5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
    </span>
    <span class="file-copy">
      <strong>{{ data.name || '文件' }}</strong>
      <small>{{ loading ? '正在准备下载…' : formatFileSize(data.size) }}</small>
    </span>
    <svg class="download-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 4v11m0 0 4-4m-4 4-4-4M5 20h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
  </button>
</template>

<style scoped>
.image-attachment { position: relative; display: block; max-width: min(340px, 62vw); min-width: 160px; min-height: 120px; padding: 0; overflow: hidden; border: 0; border-radius: 18px 18px 18px 8px; color: var(--ink-soft); background: rgba(223,236,248,.72); cursor: zoom-in; }
.image-attachment img { display: block; width: 100%; max-height: 330px; object-fit: cover; }
.image-loading { display: grid; min-height: 150px; padding: 20px; place-items: center; font-size: 12px; }
.image-hint { position: absolute; right: 9px; bottom: 9px; padding: 5px 8px; border: 1px solid rgba(255,255,255,.35); border-radius: 9px; color: white; font-size: 9px; font-weight: 700; background: rgba(16,35,63,.48); backdrop-filter: blur(10px); }
.file-attachment { display: flex; width: min(310px, 64vw); min-height: 74px; padding: 12px; align-items: center; gap: 12px; border: 0; color: var(--ink); text-align: left; background: none; cursor: pointer; }
.file-icon { display: grid; width: 46px; height: 46px; flex: 0 0 auto; place-items: center; border: 1px solid rgba(255,255,255,.86); border-radius: 15px 15px 15px 7px; color: var(--blue); background: rgba(255,255,255,.62); box-shadow: inset 0 1px 0 #fff; }
.file-icon svg { width: 23px; }
.file-copy { display: grid; min-width: 0; flex: 1; gap: 4px; }
.file-copy strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.file-copy small { color: var(--ink-soft); font-size: 10px; }
.download-icon { width: 20px; flex: 0 0 auto; color: #71859a; }

.image-attachment { border-radius: 15px 15px 15px 5px; background: var(--fill); }
.file-icon {
  border: 0;
  border-radius: 12px;
  background: rgba(0, 122, 255, 0.09);
  box-shadow: none;
}
.download-icon { color: var(--ink-faint); }
</style>
