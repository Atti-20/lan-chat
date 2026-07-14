<script setup lang="ts">
import { computed } from 'vue'
import type { User } from '../../types'
import type { ChatSection } from '../../composables/useChat'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  section: ChatSection
  user: User
  requestCount: number
  connected: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  change: [section: ChatSection]
  admin: []
  profile: []
}>()

interface RailItem {
  id: ChatSection | 'admin'
  label: string
  icon: string
}

const items: RailItem[] = [
  { id: 'messages', label: '消息', icon: 'messages' },
  { id: 'contacts', label: '好友', icon: 'contacts' },
  { id: 'groups', label: '群组', icon: 'groups' },
]
const adminItem: RailItem = {
  id: 'admin',
  label: '管理',
  icon: 'admin',
}
const navigationItems = computed(() => props.user.username === 'admin' ? [...items, adminItem] : items)
const activeIndex = computed(() => Math.max(0, items.findIndex((item) => item.id === props.section)))
const lensStyle = computed(() => ({
  '--active-index': activeIndex.value,
  '--item-count': navigationItems.value.length,
}))

function activateItem(item: RailItem): void {
  if (item.id === 'admin') {
    emit('admin')
    return
  }
  emit('change', item.id)
}
</script>

<template>
  <nav class="app-rail" aria-label="主导航" :style="lensStyle">
    <div class="rail-brand" aria-label="LanChat">
      <svg viewBox="0 0 36 36" fill="none" aria-hidden="true">
        <rect x="4" y="6" width="28" height="20" rx="6" fill="currentColor" opacity="0.15"/>
        <path d="M10 10h16a5 5 0 0 1 5 5v6a5 5 0 0 1-5 5h-6l-5 4v-4h-5a5 5 0 0 1-5-5v-6a5 5 0 0 1 5-5Z" fill="currentColor"/>
        <path d="M14 17h8M14 20h5" stroke="white" stroke-width="1.6" stroke-linecap="round"/>
      </svg>
    </div>

    <div class="rail-items">
      <span class="liquid-lens" aria-hidden="true" />
      <button
        v-for="item in navigationItems"
        :key="item.id"
        class="rail-item"
        :class="{ 'rail-item--active': section === item.id }"
        type="button"
        :aria-current="section === item.id ? 'page' : undefined"
        :aria-label="item.label"
        @click="activateItem(item)"
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <!-- 消息 -->
          <template v-if="item.icon === 'messages'">
            <path d="M8 10h8M8 14h5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
            <path d="M4 7a4 4 0 0 1 4-4h8a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4h-3.5L8 21v-4H8a4 4 0 0 1-4-4V7Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/>
          </template>
          <!-- 好友 -->
          <template v-else-if="item.icon === 'contacts'">
            <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="1.6"/>
            <path d="M5 20a7 7 0 0 1 14 0" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </template>
          <!-- 群组 -->
          <template v-else-if="item.icon === 'groups'">
            <circle cx="9" cy="8" r="3.5" stroke="currentColor" stroke-width="1.6"/>
            <path d="M3 21a6 6 0 0 1 12 0" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
            <circle cx="17.5" cy="8.5" r="2.5" stroke="currentColor" stroke-width="1.4"/>
            <path d="M21 21a4.5 4.5 0 0 0-5-4.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
          </template>
          <!-- 管理 -->
          <template v-else-if="item.icon === 'admin'">
            <path d="M12 3 5 7v5c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V7l-7-4Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/>
            <path d="m9 12 2 2 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
          </template>
        </svg>
        <span>{{ item.label }}</span>
        <b v-if="item.id === 'contacts' && requestCount" class="rail-badge">{{ Math.min(requestCount, 9) }}</b>
      </button>
    </div>

    <button class="rail-profile" type="button" aria-label="打开个人资料" @click="emit('profile')">
      <UserAvatar :name="user.nickname" :avatar="user.avatar" :size="42" :online="connected" />
    </button>
  </nav>
</template>

<style scoped>
.app-rail {
  display: flex;
  width: 78px;
  min-height: 0;
  padding: 12px 9px;
  flex-direction: column;
  align-items: center;
  border-radius: 28px 18px 18px 28px;
}
.rail-brand { display: grid; width: 52px; height: 52px; flex: 0 0 auto; place-items: center; border-radius: 19px 19px 19px 9px; color: var(--blue); background: rgba(255,255,255,.64); box-shadow: inset 0 1px 0 #fff, 0 10px 20px rgba(10,132,255,.12); }
.rail-brand svg { width: 36px; }
.rail-items { position: relative; display: grid; width: 100%; margin: auto 0; gap: 8px; }
.rail-item {
  position: relative;
  z-index: 1;
  display: grid;
  height: 58px;
  padding: 7px 2px;
  place-items: center;
  gap: 2px;
  border: 0;
  border-radius: 17px;
  color: #60748c;
  font-size: 10px;
  font-weight: 650;
  background: none;
  cursor: pointer;
  transition: color 240ms ease, transform 240ms var(--ease-liquid);
}
.rail-item:hover { color: var(--blue); transform: translateY(-1px); }
.rail-item--active { color: #0877ef; }
.rail-item svg { width: 23px; height: 23px; }
.liquid-lens {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 2px;
  width: calc(100% - 4px);
  height: 58px;
  border: 1px solid rgba(255,255,255,.92);
  border-radius: 19px 15px 19px 14px;
  background: linear-gradient(145deg, rgba(255,255,255,.88), rgba(209,235,255,.58));
  box-shadow: 0 10px 20px rgba(48, 93, 139, .12), inset 0 1px 0 #fff, inset -4px -5px 12px rgba(90,200,250,.08);
  transform: translateY(calc(var(--active-index) * 66px));
  transition: transform 420ms var(--ease-liquid), border-radius 420ms var(--ease-liquid);
}
.rail-badge { position: absolute; top: 6px; right: 7px; display: grid; min-width: 17px; height: 17px; padding: 0 4px; place-items: center; border: 2px solid rgba(246,251,255,.94); border-radius: 999px; color: white; font-size: 9px; background: var(--coral); }
.rail-profile { padding: 0; border: 0; border-radius: 16px; background: none; cursor: pointer; }

@media (max-width: 760px) {
  .app-rail {
    position: fixed;
    z-index: 40;
    right: 12px;
    bottom: max(10px, env(safe-area-inset-bottom));
    left: 12px;
    width: auto;
    height: 70px;
    min-height: 70px;
    padding: 6px 8px;
    flex-direction: row;
    border-radius: 24px;
  }
  .rail-brand,
  .rail-profile { display: none; }
  .rail-items { flex: 1; grid-template-columns: repeat(var(--item-count), 1fr); margin: 0; gap: 4px; }
  .rail-item { height: 56px; }
  .liquid-lens { left: 0; width: calc(100% / var(--item-count)); height: 56px; transform: translateX(calc(var(--active-index) * 100%)); }
}

.app-rail {
  width: 72px;
  padding: 12px 8px;
  border-width: 0 1px 0 0;
  border-color: var(--separator);
  border-radius: 0;
  background: var(--rail-bg);
  box-shadow: none;
}
.rail-brand {
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 14px;
  color: var(--blue);
  background: transparent;
  box-shadow: none;
}
.rail-brand svg { width: 32px; }
.rail-items { gap: 4px; }
.rail-item {
  height: 58px;
  border-radius: 14px;
  color: var(--ink-faint);
  font-size: 10px;
  font-weight: 600;
}
.rail-item:hover { color: var(--ink); transform: none; }
.rail-item--active { color: var(--blue); }
.liquid-lens {
  left: 3px;
  width: calc(100% - 6px);
  height: 58px;
  border-color: var(--glass-border);
  border-radius: 15px;
  background: var(--surface-glass);
  box-shadow: 0 3px 12px var(--shadow-color), inset 0 1px 1px var(--highlight);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  transform: translateY(calc(var(--active-index) * 62px));
}
.rail-badge { top: 5px; right: 6px; border-color: var(--surface); background: var(--coral); }

@media (max-width: 760px) {
  .app-rail {
    right: 14px;
    bottom: max(12px, env(safe-area-inset-bottom));
    left: 14px;
    width: auto;
    height: 64px;
    min-height: 64px;
    padding: 6px;
    border: 1px solid var(--glass-border);
    border-radius: 22px;
    background: var(--rail-bg);
    box-shadow: 0 10px 30px var(--shadow-color), inset 0 1px 0 var(--highlight);
    backdrop-filter: blur(20px) saturate(150%);
    -webkit-backdrop-filter: blur(20px) saturate(150%);
  }
  .rail-item { height: 50px; }
  .liquid-lens {
    left: 0;
    width: calc(100% / var(--item-count));
    height: 50px;
    transform: translateX(calc(var(--active-index) * 100%));
  }
}
</style>
