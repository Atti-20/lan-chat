<script lang="ts">
interface AvatarUrlCacheEntry {
  url: string
  expiresAt: number
}

const avatarUrlCache = new Map<string, AvatarUrlCacheEntry>()
const avatarUrlRequestCache = new Map<string, Promise<string>>()

const AVATAR_URL_CACHE_TTL = 5 * 60 * 1000
</script>
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

// `text` is an explicit choice, while an empty avatar remains compatible with
// older accounts. Both render the same deterministic nickname initial.
const textInitial = computed(() => {
  if (props.avatar?.startsWith('letter:')) {
    return props.avatar.slice('letter:'.length).slice(0, 1).toUpperCase() || '?'
  }
  return props.name?.slice(0, 1).toUpperCase() || '?'
})

const customColor = computed(() => {
  if (props.avatar?.startsWith('emoji:')) {
    const parts = props.avatar.split(':')
    if (parts.length >= 3 && parts[2]) return parts[2]
  }
  return ''
})

// 签名后的可用图片 URL
const resolvedImageUrl = shallowRef('')
const resolvingImage = shallowRef(false)
let avatarRequestVersion = 0

function isImageAvatar(avatar: string): boolean {
  return Boolean(avatar) && avatar !== 'text' && !avatar.startsWith('letter:') && !avatar.startsWith('emoji:') && !avatar.startsWith('svg:')
}

function isProtectedAvatar(avatar: string): boolean {
  return avatar.startsWith('/api/v1/file/content') || avatar.startsWith('/api/v1/file/preview')
}

function getCachedAvatarUrl(avatar: string): string {
  const cached = avatarUrlCache.get(avatar)
  if (!cached) return ''
  if (cached.expiresAt <= Date.now()) {
    avatarUrlCache.delete(avatar)
    return ''
  }
  return cached.url
}

function preloadImage(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve()
    image.onerror = () => reject(new Error('头像加载失败'))
    image.src = url
    if (image.complete && image.naturalWidth > 0) {
      resolve
    }
  })
}

async function resolveAvatarUrl(avatar: string): Promise<string> {
  const cachedUrl = getCachedAvatarUrl(avatar)
  if (cachedUrl) return cachedUrl
  const existingRequest = avatarUrlRequestCache.get(avatar)
  if (existingRequest) return existingRequest
  const request = (async () => {
    const url = isProtectedAvatar(avatar) ? await api.files.temporaryUrl(avatar) : avatar
    await preloadImage(url)
    avatarUrlCache.set(avatar, {
      url,
      expiresAt: Date.now() + AVATAR_URL_CACHE_TTL
    })
    return url
  })()

  avatarUrlRequestCache.set(avatar, request)

  try {
    return await request
  } finally {
    avatarUrlRequestCache.delete(avatar)
  }
}

watch(
  () => props.avatar,
  async (avatar, _previous, onCleanup) => {
    const requestVersion = ++avatarRequestVersion
    let cancelled = false
    onCleanup(() => {
      cancelled = true
    })

    if (!isImageAvatar(avatar)) {
      resolvedImageUrl.value = ''
      resolvingImage.value = false
      return
    }
    const cachedUrl = getCachedAvatarUrl(avatar)
    if (cachedUrl) {
      resolvedImageUrl.value = cachedUrl
      resolvingImage.value = false
      return
    }
    resolvingImage.value = true
    try {
      const url = await resolveAvatarUrl(avatar)
      if(cancelled || requestVersion !== avatarRequestVersion || props.avatar !== avatar) return
      resolvedImageUrl.value = url
    } catch {
      if (!cancelled && requestVersion === avatarRequestVersion) resolvingImage.value = false
    }
  },
    { immediate: true }
)

function handleAvatarImageError(): void {
  const avatar = props.avatar
  if (avatar) avatarUrlCache.delete(avatar)
  resolvedImageUrl.value = ''
  resolvingImage.value = false
}

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
    <img v-if="resolvedImageUrl" class="avatar-image" :src="resolvedImageUrl" alt="" @error="handleAvatarImageError"/>
    <span v-else-if="resolvingImage" class="avatar-loading" aria-hidden="true" 34/>
    <span v-else-if="emoji" aria-hidden="true">{{ emoji }}</span>
    <span v-else class="avatar-letter" aria-hidden="true">{{ textInitial }}</span>
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

.avatar-loading {
  width: 38%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  animation: avatar-loading-pulse 900ms ease-in-out infinite alternate;
}

@keyframes avatar-loading-pulse {
  from {
    opacity: 0.35;
    transform: scale(0.86);
  }
  to {
    opacity: 0.75;
    transform: scale(1);
  }
}

.avatar-letter {
  text-shadow: 0 1px 2px rgba(10, 30, 55, 0.2);
}

.online-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: clamp(7px, 26%, 18px);
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
