<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useGlassChromeMetrics } from '../../composables/useGlassChromeMetrics'
import type { User } from '../../types'
import type { ChatSection } from '../../composables/useChat'
import BrandLogo from '../base/BrandLogo.vue'
import UserAvatar from '../base/UserAvatar.vue'
import UiBadge from '../base/UiBadge.vue'
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
const railElement = shallowRef<HTMLElement | null>(null)
useGlassChromeMetrics(railElement, 'navigation')
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
  { id: 'contacts', label: '联系人', icon: 'contacts' },
  { id: 'groups', label: '群聊', icon: 'groups' },
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
  <nav ref="railElement" class="app-rail apple-structural-surface" :class="{ 'app-rail--admin': navigationItems.length > 4 }" aria-label="主导航" :style="lensStyle">
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
        <UiBadge v-if="item.id === 'messages' && messageCount" class="rail-badge" tone="unread" :value="messageCount > 99 ? '99+' : messageCount" :label="`${messageCount} 条未读消息`" />
        <UiBadge v-if="item.id === 'contacts' && requestCount" class="rail-badge" tone="unread" :value="requestCount > 99 ? '99+' : requestCount" :label="`${requestCount} 条待处理好友申请`" />
        <UiBadge v-else-if="item.id === 'broadcasts' && broadcastCount" class="rail-badge" tone="unread" :value="broadcastCount > 99 ? '99+' : broadcastCount" :label="`${broadcastCount} 条待处理广播`" />
      </button>
    </div>

    <button class="rail-item rail-profile apple-list-row" type="button" aria-label="打开个人资料" @click="emit('profile')">
      <UserAvatar :name="user.nickname" :avatar="user.avatar" :size="26" :online="connected" />
      <span class="rail-label">我的</span>
    </button>
  </nav>
</template>

<style scoped>
/* Native iOS is the reference. This is an accessible Web equivalent, not a
   claim to run UIKit or duplicate Apple's optical renderer. */
.app-rail {
  display: flex; width: var(--rail-width, 72px); min-height: 0;
  padding: 12px 8px; flex-direction: column; align-items: center;
  border: 0; border-right: 1px solid var(--separator); border-radius: 0;
  background: var(--mx-color-glass-regular); box-shadow: none;
}
.rail-brand { display: grid; width: 44px; height: 44px; flex: 0 0 auto; place-items: center; }
.rail-brand .brand-logo { width: 32px; height: 32px; }
.rail-items { position: relative; display: grid; width: 100%; margin: auto 0; gap: 4px; }
.rail-item {
  position: relative; z-index: 1; display: grid; min-height: 58px;
  padding: 6px 2px; grid-template-rows: 26px auto; align-content: center;
  justify-items: center; gap: 2px; border: 0; border-radius: 20px;
  color: var(--ink); font-size: .75rem; font-weight: 600;
  background: none; cursor: pointer;
  transition: color var(--duration-fast) ease;
}
.rail-label { display: block; min-width: 0; line-height: 1.2; }
.rail-item--active { color: var(--accent-text); }
.rail-item:focus-visible { outline: 2px solid var(--accent-text); outline-offset: -2px; }
.rail-item .ui-icon { width: 24px; height: 24px; }
.liquid-lens {
  position: absolute; z-index: 0; top: 0; left: 0; pointer-events: none;
  width: var(--mx-lens-width, 100%); height: var(--mx-lens-height, 58px);
  transform: translate(var(--mx-lens-x, 0px), var(--mx-lens-y, 0px));
  border: 1px solid var(--mx-color-glass-rim); border-radius: 20px;
  background: var(--mx-color-glass-readable);
  box-shadow: 0 2px 8px var(--shadow-color), inset 0 1px 0 var(--highlight);
  transition: transform 240ms var(--ease-liquid), width 240ms var(--ease-liquid), height 240ms var(--ease-liquid);
}
.rail-badge {
  position: absolute; top: 2px; right: 2px; display: grid;
  border: 2px solid var(--surface); border-radius: var(--radius-pill);
}
.rail-profile { width: 100%; flex: 0 0 auto; margin-top: 8px; }

@media (max-width: 760px) {
  .app-rail {
    position: fixed; z-index: 40;
    left: 50%; right: auto; bottom: max(var(--mx-component-glass-navigation-inset), env(safe-area-inset-bottom));
    width: min(calc(100% - 24px), var(--mx-component-glass-navigation-max-width));
    height: auto; min-height: var(--mx-component-glass-navigation-min-height);
    padding: 6px; gap: 6px; flex-direction: row; transform: translateX(-50%);
    border: 1px solid var(--mx-color-glass-rim); border-radius: 30px;
    background: var(--mx-color-glass-regular);
    background-image: linear-gradient(125deg, var(--highlight-soft), transparent 32%, transparent 76%, var(--highlight));
    box-shadow: 0 10px 32px var(--shadow-color), inset 0 1px 0 var(--highlight);
    -webkit-backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(150%);
    backdrop-filter: blur(var(--mx-component-glass-fallback-blur)) saturate(150%);
  }
  .rail-brand { display: none; }
  .rail-items { min-width: 0; flex: var(--item-count) 1 0; grid-template-columns: repeat(var(--item-count), minmax(0,1fr)); margin: 0; gap: 0; }
  .rail-item { min-width: 0; min-height: var(--mx-component-glass-control-size); height: auto; }
  .rail-profile { width: auto; min-width: 0; flex: 1 1 0; margin: 0; border-left: 1px solid var(--separator); border-radius: 0; }
  .rail-profile :deep(.avatar) { margin: 0; }
  .liquid-lens { border-radius: 24px; }
}
/* An admin has six visible controls. Wrap at the smallest sizes rather than
   shrink the touch targets or silently remove an authorized destination. */
@media (max-width: 380px) {
  .app-rail--admin { align-items: stretch; }
  .app-rail--admin .rail-items { grid-template-columns: repeat(3, minmax(48px,1fr)); flex: 3; }
}
@media (prefers-reduced-motion: reduce) { .liquid-lens, .rail-item { transition: none; } }
@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) {
  .app-rail, .liquid-lens { background: var(--surface); background-image: none; -webkit-backdrop-filter: none; backdrop-filter: none; box-shadow: none; border-color: var(--mx-color-glass-accessible-border); }
}
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .app-rail, .liquid-lens { background: var(--surface); background-image: none; }
}
@media (forced-colors: active) {
  .app-rail, .liquid-lens { background: Canvas; box-shadow: none; border: 1px solid CanvasText; }
  .rail-item { color: ButtonText; }
  .rail-item--active { outline: 2px solid Highlight; }
  .rail-badge { background: Highlight; color: HighlightText; border-color: Canvas; }
}

@media (min-width: 761px) {
  .rail-items { min-height: 0; overflow-y: auto; }
}
</style>
