<script setup lang="ts">
import { computed } from 'vue'

/**
 * The app keeps one small icon surface so every icon follows Lucide's
 * 24 × 24, currentColor, round-line conventions without adding a runtime
 * icon dependency to the bundled application.
 */
export type IconName =
  | 'brand'
  | 'messages'
  | 'contacts'
  | 'groups'
  | 'admin'
  | 'search'
  | 'plus'
  | 'image'
  | 'paperclip'
  | 'flame'
  | 'send'
  | 'file'
  | 'download'
  | 'back'
  | 'arrow-right'
  | 'close'
  | 'edit'
  | 'pin'
  | 'bell'
  | 'bell-off'
  | 'trash'
  | 'check'
  | 'monitor'
  | 'lock'
  | 'sun'
  | 'moon'

interface Props {
  name: IconName
  size?: number
  label?: string
  strokeWidth?: number
}

const props = withDefaults(defineProps<Props>(), {
  size: undefined,
  label: '',
  strokeWidth: 2,
})

const viewBox = computed(() => props.name === 'brand' ? '0 0 36 36' : '0 0 24 24')
const iconStyle = computed(() => props.size ? {
  width: `${props.size}px`,
  height: `${props.size}px`,
} : undefined)
const iconClasses = computed(() => [
  'ui-icon',
  'lucide',
  `lucide-${props.name}-icon`,
  `lucide-${props.name}`,
])
</script>

<template>
  <svg
    :class="iconClasses"
    :viewBox="viewBox"
    width="24"
    height="24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    :style="iconStyle"
    :role="label ? 'img' : undefined"
    :aria-label="label || undefined"
    :aria-hidden="label ? undefined : true"
  >
    <!-- Product mark: the only non-24px glyph, kept as a line icon to match the set. -->
    <template v-if="name === 'brand'">
      <path d="M10 6h16a5 5 0 0 1 5 5v7a5 5 0 0 1-5 5h-7l-6 5v-5h-3a5 5 0 0 1-5-5v-7a5 5 0 0 1 5-5Z" />
      <path d="M11 12h14M11 17h8" />
    </template>

    <template v-else-if="name === 'messages'">
      <path d="M16 10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H8l-4 4V12a2 2 0 0 1 2-2h10Z" />
      <path d="M18 14h2a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v2" />
    </template>

    <template v-else-if="name === 'contacts'">
      <path d="M16 21a6 6 0 0 0-12 0" />
      <circle cx="10" cy="7" r="4" />
      <path d="M22 21a6 6 0 0 0-4-5.65" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </template>

    <template v-else-if="name === 'groups'">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </template>

    <template v-else-if="name === 'admin'">
      <path d="M20 13c0 5-3.5 7.5-8 8.5C7.5 20.5 4 18 4 13V5l8-3 8 3v8Z" />
      <path d="m9 12 2 2 4-4" />
    </template>

    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="8" />
      <path d="m21 21-4.3-4.3" />
    </template>

    <template v-else-if="name === 'plus'">
      <path d="M5 12h14" />
      <path d="M12 5v14" />
    </template>

    <template v-else-if="name === 'image'">
      <rect width="18" height="18" x="3" y="3" rx="2" />
      <circle cx="9" cy="9" r="2" />
      <path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" />
    </template>

    <template v-else-if="name === 'paperclip'">
      <path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
    </template>

    <template v-else-if="name === 'flame'">
      <path d="M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0 5 5 0 0 1 1-3 1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4" />
    </template>

    <template v-else-if="name === 'send'">
      <path d="m22 2-7 20-4-9-9-4Z" />
      <path d="M22 2 11 13" />
    </template>

    <template v-else-if="name === 'file'">
      <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
      <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" />
    </template>

    <template v-else-if="name === 'download'">
      <path d="M12 15V3" />
      <path d="m7 10 5 5 5-5" />
      <path d="M5 21h14" />
    </template>

    <template v-else-if="name === 'back'">
      <path d="m12 19-7-7 7-7" />
      <path d="M19 12H5" />
    </template>

    <template v-else-if="name === 'arrow-right'">
      <path d="M5 12h14" />
      <path d="m12 5 7 7-7 7" />
    </template>

    <template v-else-if="name === 'close'">
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </template>

    <template v-else-if="name === 'edit'">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </template>

    <template v-else-if="name === 'pin'">
      <path d="M12 17v5" />
      <path d="M9 3h6l1 5 3 3v1H5v-1l3-3 1-5Z" />
    </template>

    <template v-else-if="name === 'bell'">
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </template>

    <template v-else-if="name === 'bell-off'">
      <path d="M13.73 21a2 2 0 0 1-3.46 0" />
      <path d="M18.63 13A17.9 17.9 0 0 1 18 8" />
      <path d="M6.26 6.26A6 6 0 0 0 6 8c0 7-3 7-3 9h14" />
      <path d="m2 2 20 20" />
    </template>

    <template v-else-if="name === 'trash'">
      <path d="M3 6h18" />
      <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
      <path d="M10 11v6M14 11v6" />
    </template>

    <template v-else-if="name === 'check'">
      <path d="m20 6-11 11-5-5" />
    </template>

    <template v-else-if="name === 'monitor'">
      <rect width="20" height="14" x="2" y="3" rx="2" />
      <path d="M8 21h8M12 17v4" />
    </template>

    <template v-else-if="name === 'lock'">
      <rect width="18" height="11" x="3" y="11" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      <circle cx="12" cy="16" r="1" />
    </template>

    <template v-else-if="name === 'sun'">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </template>

    <template v-else-if="name === 'moon'">
      <path d="M20.985 12.486a9 9 0 1 1-9.471-9.471 7 7 0 0 0 9.471 9.471Z" />
    </template>
  </svg>
</template>

<style scoped>
.ui-icon {
  display: block;
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  overflow: visible;
}
</style>
