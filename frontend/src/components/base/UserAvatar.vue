<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  name: string
  avatar?: string
  size?: number
  online?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  avatar: '',
  size: 46,
  online: false,
})

const legacyEmoji = ['🫧', '🐼', '🐰', '🐸', '🦉', '🐥', '🦊', '🐻', '🐧', '🦩', '🐨', '🦁']
const hue = computed(() => {
  let hash = 0
  for (const char of props.name || '?') hash = (hash * 31 + char.charCodeAt(0)) % 360
  return hash
})
const emoji = computed(() => {
  if (props.avatar?.startsWith('emoji:')) return props.avatar.split(':')[1]
  if (props.avatar?.startsWith('svg:')) {
    const index = Number(props.avatar.split(':')[1] || 0)
    return legacyEmoji[index % legacyEmoji.length]
  }
  return ''
})
const imageUrl = computed(() => props.avatar && !props.avatar.startsWith('emoji:') && !props.avatar.startsWith('svg:')
  ? props.avatar
  : '')
const avatarStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.max(14, props.size * 0.4)}px`,
  background: `linear-gradient(145deg, hsl(${hue.value} 78% 72%), hsl(${(hue.value + 42) % 360} 72% 54%))`,
}))
</script>

<template>
  <span class="avatar" :style="avatarStyle" :aria-label="`${name}的头像`">
    <img v-if="imageUrl" class="avatar-image" :src="imageUrl" alt="" />
    <span v-else-if="emoji" aria-hidden="true">{{ emoji }}</span>
    <span v-else class="avatar-letter" aria-hidden="true">{{ name?.slice(0, 1).toUpperCase() || '?' }}</span>
    <span v-if="online" class="online-dot" aria-label="在线" />
  </span>
</template>

<style scoped>
.avatar {
  position: relative;
  display: inline-grid;
  flex: 0 0 auto;
  overflow: visible;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 36%;
  color: #fff;
  font-weight: 750;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55), 0 7px 18px rgba(41, 74, 108, 0.16);
}

.avatar-image {
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
}

.avatar-letter {
  text-shadow: 0 1px 2px rgba(10, 30, 55, 0.2);
}

.online-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 26%;
  min-width: 10px;
  aspect-ratio: 1;
  border: 2px solid rgba(245, 251, 255, 0.96);
  border-radius: 50%;
  background: #30d158;
  box-shadow: 0 2px 7px rgba(48, 209, 88, 0.4);
}

.avatar {
  border-color: rgba(255, 255, 255, 0.88);
  border-radius: 50%;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.46), 0 2px 7px rgba(29, 29, 31, 0.1);
}
.online-dot { border-color: #fff; background: var(--green); box-shadow: none; }
</style>
