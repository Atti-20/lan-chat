<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { api } from '../../services/api'

const props = defineProps<{
  source: string
  alt: string
}>()

const resolved = shallowRef('')

watch(() => props.source, async (source, _previous, onCleanup) => {
  let cancelled = false
  onCleanup(() => {
    cancelled = true
  })
  resolved.value = ''
  try {
    const url = await api.files.temporaryUrl(source)
    if (!cancelled) resolved.value = url
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
.evidence-placeholder { display: block; min-height: 120px; border-radius: 12px; background: var(--fill); }
</style>
