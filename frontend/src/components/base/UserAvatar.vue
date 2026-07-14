<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { api } from '../../services/api'

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

const customColor = computed(() => {
  if (props.avatar?.startsWith('emoji:')) {
    const parts = props.avatar.split(':')
    if (parts.length >= 3 && parts[2]) return parts[2]
  }
  return ''
})

// 判断是否是需要签名的受保护路径
const isProtectedUrl = computed(() =>
  props.avatar?.startsWith('/api/v1/file/content/') || false
)

// 判断是否是图片 URL（受保护或普通 URL）
const isImageAvatar = computed(() =>
  props.avatar && !props.avatar.startsWith('emoji:') && !props.avatar.startsWith('svg:')
)

// 签名后的可用图片 URL
const resolvedImageUrl = shallowRef('')

watch(
  () => props.avatar,
  async (avatar) => {
    if (!avatar || avatar.startsWith('emoji:') || avatar.startsWith('svg:')) {
      resolvedImageUrl.value = ''
      return
    }
    if (avatar.startsWith('/api/v1/file/content/') || avatar.startsWith('/api/v1/file/preview/')) {
      // 受保护路径，需要获取签名 URL
      try {
        const signedUrl = await api.files.temporaryUrl(avatar)
        resolvedImageUrl.value = signedUrl
      } catch {
        resolvedImageUrl.value = ''
      }
    } else {
      // 普通 URL（http:// 等），直接使用
      resolvedImageUrl.value = avatar
    }
  },
  { immediate: true },
)

const avatarStyle = computed(() => {
  const base: Record<string, string> = {
    width: `${props.size}px`,
    height: `${props.size}px`,
    fontSize: `${Math.max(14, props.size * 0.4)}px`,
  }
  if (customColor.value) {
    const color = customColor.value
    base.background = `linear-gradient(145deg, ${color}, ${adjustColor(color, -18)})`
  } else {
    base.background = `linear-gradient(145deg, hsl(${hue.value} 78% 72%), hsl(${(hue.value + 42) % 360} 72% 54%))`
  }
  return base
})

function adjustColor(hex: string, amount: number): string {
  const clean = hex.replace('#', '')
  const num = parseInt(clean, 16)
  let r = Math.min(255, Math.max(0, ((num >> 16) & 0xFF) + amount))
  let g = Math.min(255, Math.max(0, ((num >> 8) & 0xFF) + amount))
  let b = Math.min(255, Math.max(0, (num & 0xFF) + amount))
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
}
</script>

<template>
  <span class="avatar" :style="avatarStyle" :aria-label="`${name}的头像`">
    <img v-if="resolvedImageUrl" class="avatar-image" :src="resolvedImageUrl" alt="" />
    <span v-else-if="emoji" aria-hidden="true">{{ emoji }}</span>
    <span v-else-if="isImageAvatar" class="avatar-letter" aria-hidden="true">{{ name?.slice(0, 1).toUpperCase() || '?' }}</span>
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
  border-color: var(--highlight);
  border-radius: 50%;
  box-shadow: inset 0 1px 0 var(--highlight), 0 2px 7px var(--shadow-color);
}
.online-dot { border-color: var(--surface); background: var(--green); box-shadow: none; }
</style>
