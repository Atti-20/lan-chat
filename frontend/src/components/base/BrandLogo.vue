<script setup lang="ts">
import { computed } from 'vue'
import meshxLogo from '../../assets/meshx-logo.svg'
import meshxLogoDark from '../../assets/meshx-logo-dark.svg'
import { useTheme } from '../../composables/useTheme'

interface Props {
  decorative?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  decorative: false,
})

const { mode } = useTheme()
// Login and navigation use the transparent brand marks, not the app-icon art.
const source = computed(() => mode.value === 'dark' ? meshxLogo : meshxLogoDark)
const alt = computed(() => props.decorative ? '' : 'MeshX')
</script>

<template>
  <img class="brand-logo" :src="source" :alt="alt" :aria-hidden="decorative || undefined" />
</template>

<style scoped>
.brand-logo {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
</style>
