<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import ToastStack from './components/base/ToastStack.vue'
import AuthView from './views/AuthView.vue'
import ChatView from './views/ChatView.vue'
import WelcomeView from './views/WelcomeView.vue'
import {installNotificationSoundUnlock} from "./services/notificationSound";

const base = import.meta.env.BASE_URL.replace(/\/$/, '') // e.g. '/app'
const path = window.location.pathname
let removeNotificationSoundUnlock: (() => void) | null = null
onMounted(() => {removeNotificationSoundUnlock = installNotificationSoundUnlock()})
onBeforeUnmount(() => {
  removeNotificationSoundUnlock?.()
  removeNotificationSoundUnlock = null
})
const route = computed(() => {
  if (path === `${base}/chat` || path === '/chat') return 'chat'
  if (path === `${base}/welcome` || path === '/welcome') return 'welcome'
  return 'auth'
})

</script>

<template>
  <ChatView v-if="route === 'chat'" />
  <WelcomeView v-else-if="route === 'welcome'" />
  <AuthView v-else />
  <ToastStack />
</template>
