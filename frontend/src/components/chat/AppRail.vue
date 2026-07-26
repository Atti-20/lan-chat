<script setup lang="ts">
import { computed } from 'vue'
import type { User } from '../../types'
import type { ChatSection } from '../../composables/useChat'
import BrandLogo from '../base/BrandLogo.vue'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon, { type IconName } from '../base/UiIcon.vue'

interface Props {
  section: ChatSection
  user: User
  requestCount: number
  messageCount?: number
  broadcastCount?: number
  connected: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  change: [section: ChatSection]
  profile: []
}>()

interface RailItem {
  id: ChatSection
  label: string
  icon: IconName
}

const items: RailItem[] = [
  { id: 'messages', label: '消息', icon: 'messages' },
  { id: 'contacts', label: '好友', icon: 'contacts' },
  { id: 'groups', label: '群组', icon: 'groups' },
  { id: 'broadcasts', label: '广播', icon: 'bell' },
]
const adminItem: RailItem = {
  id: 'admin',
  label: '管理',
  icon: 'admin',
}
const navigationItems = computed(() => props.user.username === 'admin' ? [...items, adminItem] : items)
const activeIndex = computed(() => Math.max(0, navigationItems.value.findIndex((item) => item.id === props.section)))
const lensStyle = computed(() => ({
  '--active-index': activeIndex.value,
  '--item-count': navigationItems.value.length,
}))

function activateItem(item: RailItem): void {
  emit('change', item.id)
}
</script>

<template>
  <nav class="app-rail apple-structural-surface" aria-label="主导航" :style="lensStyle">
    <div class="rail-brand" role="img" aria-label="MeshX">
      <BrandLogo decorative />
    </div>

    <div class="rail-items">
      <span class="liquid-lens" aria-hidden="true" />
      <button
        v-for="item in navigationItems"
        :key="item.id"
        class="rail-item apple-list-row"
        :class="{ 'rail-item--active': section === item.id }"
        type="button"
        :aria-current="section === item.id ? 'page' : undefined"
        :aria-label="item.label"
        @click="activateItem(item)"
      >
        <UiIcon :name="item.icon" :size="23" />
        <span class="rail-label">{{ item.label }}</span>
        <b v-if="item.id === 'messages'&& messageCount" class="rail-badge">{{messageCount > 99 ? '99+' : messageCount}}</b>
        <b v-if="item.id === 'contacts' && requestCount" class="rail-badge">{{ Math.min(requestCount, 9) }}</b>
        <b v-else-if="item.id === 'broadcasts' && broadcastCount" class="rail-badge">{{ Math.min(broadcastCount, 9) }}</b>
      </button>
    </div>

    <button class="rail-item rail-profile apple-list-row" type="button" aria-label="打开个人资料" @click="emit('profile')">
      <UserAvatar :name="user.nickname" :avatar="user.avatar" :size="26" :online="connected" />
      <span class="rail-label">我的</span>
    </button>
  </nav>
</template>

<style scoped>
.app-rail {
  display: flex;
  /* 与 ChatView 的 --rail-width 网格列保持一致（平板压缩到 68px）。 */
  width: var(--rail-width, 72px);
  min-height: 0;
  padding: 12px 8px;
  flex-direction: column;
  align-items: center;
  border-width: 0 1px 0 0;
  border-color: var(--separator);
  border-radius: 0;
  background: var(--rail-bg);
  box-shadow: none;
}
.rail-brand {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 14px;
  color: var(--blue);
  background: transparent;
  box-shadow: none;
}
.rail-brand .brand-logo { width: 32px; height: 32px; }
.rail-items { position: relative; display: grid; width: 100%; margin: auto 0; gap: 4px; }
.rail-item {
  position: relative;
  z-index: 1;
  display: grid;
  height: 58px;
  padding: 7px 2px;
  grid-template-rows: 28px 14px;
  align-content: center;
  justify-items: center;
  row-gap: 2px;
  border: 0;
  border-radius: 14px;
  color: var(--ink-faint);
  font-size: var(--font-micro);
  font-weight: 600;
  background: none;
  cursor: pointer;
  transition: color 180ms ease, background-color 180ms ease;
}
.rail-label { display: block; min-width: 0; line-height: 14px; }
.rail-item:hover { color: var(--ink); }
.rail-item--active { color: var(--blue); }
.rail-item .ui-icon { width: 23px; height: 23px; }
.liquid-lens {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 3px;
  width: calc(100% - 6px);
  height: 58px;
  border: 1px solid var(--glass-border);
  border-radius: 15px;
  background: var(--surface-glass);
  box-shadow: 0 3px 12px var(--shadow-color), inset 0 1px 1px var(--highlight);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  transform: translateY(calc(var(--active-index) * 62px));
  transition: transform 360ms var(--ease-liquid);
}
.rail-badge {
  position: absolute;
  top: 5px;
  right: 6px;
  display: grid;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  place-items: center;
  border: 2px solid var(--surface);
  border-radius: 999px;
  color: white;
  font-size: var(--font-caption);
  background: var(--coral);
}
.rail-profile { width: 100%; min-height: 58px; flex: 0 0 auto; }

@media (max-width: 760px) {
  .app-rail {
    position: fixed;
    z-index: 40;
    right: auto;
    bottom: max(12px, env(safe-area-inset-bottom));
    left: 50%;
    width: min(calc(100% - 28px), 560px);
    height: 64px;
    min-height: 64px;
    padding: 6px;
    border: 1px solid var(--glass-border);
    border-radius: 22px;
    background: var(--rail-bg);
    box-shadow: 0 10px 30px var(--shadow-color), inset 0 1px 0 var(--highlight);
    backdrop-filter: blur(20px) saturate(150%);
    -webkit-backdrop-filter: blur(20px) saturate(150%);
    transform: translateX(-50%);
    flex-direction: row;
  }
  .rail-brand { display: none; }
  .rail-item {
    height: 50px;
    grid-template-rows: 28px 14px;
  }
  .rail-items {
    min-width: 0;
    flex: var(--item-count) 1 0;
    grid-template-columns: repeat(var(--item-count), 1fr);
    margin: 0;
  }
  .rail-profile {
    width: auto;
    min-width: 0;
    height: 50px;
    min-height: 50px;
    flex: 1 1 0;
  }
  .rail-profile :deep(.avatar) { margin: 0; }
  .liquid-lens {
    left: 0;
    width: calc(100% / var(--item-count));
    height: 50px;
    transform: translateX(calc(var(--active-index) * 100%));
  }
}

@media (prefers-reduced-motion: reduce) {
  .liquid-lens,
  .rail-item { transition: none; }
}
</style>
