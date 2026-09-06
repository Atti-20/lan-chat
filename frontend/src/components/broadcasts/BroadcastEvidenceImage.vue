<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { nativeBridge } from '../../platform/nativeBridge'
import { nodeFetch } from '../../platform/nativeTransport'
import { api } from '../../services/api'

const props = defineProps<{
  source: string
  alt: string
}>()

const resolved = shallowRef('')

watch(() => props.source, async (source, _previous, onCleanup) => {
  let cancelled = false
  let objectUrl = ''
  onCleanup(() => {
    cancelled = true
    if (objectUrl) URL.revokeObjectURL(objectUrl)
  })
  resolved.value = ''
  try {
    const url = await api.files.temporaryUrl(source)
    if (nativeBridge.runtime() === 'tauri') {
      const response = await nodeFetch(url)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const blob = await response.blob()
      if (blob.size === 0) throw new Error('empty evidence image')
      if (cancelled) return
      objectUrl = URL.createObjectURL(blob)
    }
    if (!cancelled) resolved.value = objectUrl || url
  } catch {
    // Keep the evidence slot empty when its short-lived authorization fails.
  }
}, { immediate: true })
</script>

<template>
  <img v-if="resolved" :src="resolved" :alt="alt" />
  <span v-else class="evidence-placeholder" aria-label="广播附件暂不可用" />
</template>

<style scoped>
.evidence-placeholder { display: block; min-height: 120px; border-radius: var(--radius-control); background: var(--fill); }
</style>
