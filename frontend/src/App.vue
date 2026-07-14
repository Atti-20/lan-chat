<script setup lang="ts">
import { computed, onMounted } from 'vue'
import ToastStack from './components/base/ToastStack.vue'
import { useAuth } from './composables/useAuth'
import AuthView from './views/AuthView.vue'
import ChatView from './views/ChatView.vue'
import WelcomeView from './views/WelcomeView.vue'

const auth = useAuth()
const base = import.meta.env.BASE_URL.replace(/\/$/, '') // e.g. '/app'
const path = window.location.pathname
const route = computed(() => {
  if (path === `${base}/chat` || path === '/chat') return 'chat'
  if (path === `${base}/welcome` || path === '/welcome') return 'welcome'
  return 'auth'
})

onMounted(() => {
  if (route.value !== 'auth' && !auth.isAuthenticated.value) {
    window.location.replace(base || '/')
  }
})
</script>

<template>
  <ChatView v-if="route === 'chat'" />
  <WelcomeView v-else-if="route === 'welcome'" />
  <AuthView v-else />
  <ToastStack />
</template>
