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
  path: string
}

const items: RailItem[] = [
  { id: 'messages', label: '消息', path: 'M5 6.5A3.5 3.5 0 0 1 8.5 3h7A3.5 3.5 0 0 1 19 6.5v5a3.5 3.5 0 0 1-3.5 3.5H11l-4.5 4v-4A3.5 3.5 0 0 1 5 11.5v-5Z' },
  { id: 'contacts', label: '好友', path: 'M12 12a4 4 0 1 0 0 -8 4 4 0 0 0 0 8Zm7 8a7 7 0 0 0 -14 0' },
  { id: 'groups', label: '群组', path: 'M16 11a3 3 0 1 0 0 -6m-8 6a3 3 0 1 0 0 -6m8 15v-1a5 5 0 0 0 -5 -5H8a5 5 0 0 0 -5 5v1m18 0v-1a5 5 0 0 0 -3 -4.58' },
  { id: 'requests', label: '申请', path: 'M9 12a4 4 0 1 0 0 -8 4 4 0 0 0 0 8Zm-6 8a6 6 0 0 1 12 0m4 -8v6m3 -3h-6' },
]
const adminItem: RailItem = {
  id: 'admin',
  label: '管理',
  path: 'M12 3 4.5 6v5.5c0 4.7 3.2 7.8 7.5 9.5 4.3-1.7 7.5-4.8 7.5-9.5V6L12 3Zm-2.5 9 1.7 1.7 3.5-3.7',
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
  <nav class="app-rail glass-surface" aria-label="主导航" :style="lensStyle">
    <div class="rail-brand" aria-label="LanChat">
      <svg viewBox="0 0 36 36" fill="none" aria-hidden="true">
        <path d="M8.5 8h19A4.5 4.5 0 0 1 32 12.5v7a4.5 4.5 0 0 1-4.5 4.5H18l-8 6v-6H8.5A4.5 4.5 0 0 1 4 19.5v-7A4.5 4.5 0 0 1 8.5 8Z" fill="currentColor" />
        <path d="M12 15h12m-12 5h8" stroke="white" stroke-width="2" stroke-linecap="round" />
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
          <path :d="item.path" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>{{ item.label }}</span>
        <b v-if="item.id === 'requests' && requestCount" class="rail-badge">{{ Math.min(requestCount, 9) }}</b>
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
  .rail-items { grid-template-columns: repeat(var(--item-count), 1fr); margin: 0; gap: 4px; }
  .rail-item { height: 56px; }
  .liquid-lens { left: 0; width: calc(100% / var(--item-count)); height: 56px; transform: translateX(calc(var(--active-index) * 100%)); }
}

/* Liquid Glass 只留在导航层，内容区域保持安静。 */
.app-rail {
  width: 72px;
  padding: 12px 8px;
  border-width: 0 1px 0 0;
  border-color: var(--separator);
  border-radius: 0;
  background: rgba(247, 248, 250, 0.74);
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
  border-color: rgba(255, 255, 255, 0.84);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 3px 12px rgba(28, 39, 54, 0.08), inset 0 1px 1px rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  transform: translateY(calc(var(--active-index) * 62px));
}
.rail-badge { top: 5px; right: 6px; border-color: rgba(255, 255, 255, 0.9); background: var(--coral); }

@media (max-width: 760px) {
  .app-rail {
    right: 14px;
    bottom: max(12px, env(safe-area-inset-bottom));
    left: 14px;
    width: auto;
    height: 64px;
    min-height: 64px;
    padding: 6px;
    border: 1px solid rgba(255, 255, 255, 0.82);
    border-radius: 22px;
    background: rgba(247, 248, 250, 0.72);
    box-shadow: 0 10px 30px rgba(27, 39, 56, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.92);
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
