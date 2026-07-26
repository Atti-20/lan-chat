<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { App } from '@capacitor/app'
import {
  DESKTOP_NAVIGATION_EVENT,
} from '../../platform/desktopNavigation'
import {
  nativeBridge,
  type DesktopNavigationTarget,
} from '../../platform/nativeBridge'
import {
  nextBackAction,
  type NavigationBackAction,
  type NavigationBackState,
} from './navigationState'

interface Props {
  backState: NavigationBackState
}

const props = defineProps<Props>()
const emit = defineEmits<{
  navigate: [target: DesktopNavigationTarget]
  viewportChange: [width: number]
  back: [action: NavigationBackAction]
}>()

let removeAndroidBackListener: (() => void) | null = null

function updateViewport(): void {
  const visualViewport = window.visualViewport
  const viewportHeight = Math.round(visualViewport?.height ?? window.innerHeight)
  document.documentElement.style.setProperty('--app-viewport-height', `${viewportHeight}px`)
  emit('viewportChange', window.innerWidth)
}

function handleNavigation(event: Event): void {
  if (!(event instanceof CustomEvent)) return
  emit('navigate', event.detail as DesktopNavigationTarget)
}

async function handleAndroidBack(): Promise<void> {
  const action = nextBackAction(props.backState)
  emit('back', action)
  if (action === 'exit') await App.exitApp()
}

onMounted(async () => {
  updateViewport()
  window.addEventListener('resize', updateViewport)
  window.visualViewport?.addEventListener('resize', updateViewport)
  window.visualViewport?.addEventListener('scroll', updateViewport)
  window.addEventListener(DESKTOP_NAVIGATION_EVENT, handleNavigation)

  if (nativeBridge.runtime() === 'capacitor') {
    const listener = await App.addListener('backButton', () => {
      void handleAndroidBack()
    })
    removeAndroidBackListener = () => listener.remove()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport)
  window.visualViewport?.removeEventListener('resize', updateViewport)
  window.visualViewport?.removeEventListener('scroll', updateViewport)
  window.removeEventListener(DESKTOP_NAVIGATION_EVENT, handleNavigation)
  removeAndroidBackListener?.()
  removeAndroidBackListener = null
})
</script>

<template></template>
