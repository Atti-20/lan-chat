<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
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
  composerSubmitRevision?: number
}

const props = withDefaults(defineProps<Props>(), {
  composerSubmitRevision: 0,
})
const emit = defineEmits<{
  navigate: [target: DesktopNavigationTarget]
  viewportChange: [width: number]
  back: [action: NavigationBackAction]
}>()

let removeAndroidBackListener: (() => void) | null = null
let viewportFrame: number | null = null
let focusResetFrame: number | null = null

function updateViewport(): void {
  const visualViewport = window.visualViewport
  const viewportHeight = Math.round(visualViewport?.height ?? window.innerHeight)
  document.documentElement.style.setProperty('--app-viewport-height', `${viewportHeight}px`)
  emit('viewportChange', window.innerWidth)
}

function scheduleViewportUpdate(): void {
  if (viewportFrame !== null) cancelAnimationFrame(viewportFrame)
  viewportFrame = requestAnimationFrame(() => {
    viewportFrame = null
    updateViewport()
    // Keyboard close changes the visual viewport after the textarea loses
    // focus.  Re-anchor after every viewport transition as well as focus
    // transitions so iOS cannot retain the old document scroll offset after a
    // message is sent.
    keepMobileViewportAnchored()
  })
}

function keepMobileViewportAnchored(): void {
  if (!window.matchMedia('(max-width: 760px)').matches) return
  if (focusResetFrame !== null) cancelAnimationFrame(focusResetFrame)
  // iOS can retain a stale document offset as the visual viewport shrinks for
  // the keyboard. The thread owns scrolling, so keep the document at origin.
  focusResetFrame = requestAnimationFrame(() => {
    focusResetFrame = null
    window.scrollTo(0, 0)
    document.documentElement.scrollTop = 0
    document.body.scrollTop = 0
  })
}

function handleNavigation(event: Event): void {
  if (!(event instanceof CustomEvent)) return
  emit('navigate', event.detail as DesktopNavigationTarget)
}

// A touch send keeps the textarea as iOS's first responder, so there is no
// focusout event to drive the normal reset.  This explicit revision arrives
// after the optimistic message has been inserted and preserves the keyboard
// for the next message.
watch(
  () => props.composerSubmitRevision,
  () => { keepMobileViewportAnchored() },
  { flush: 'post' },
)

async function handleAndroidBack(): Promise<void> {
  const action = nextBackAction(props.backState)
  emit('back', action)
  if (action === 'exit') await App.exitApp()
}

onMounted(async () => {
  updateViewport()
  window.addEventListener('resize', scheduleViewportUpdate)
  window.visualViewport?.addEventListener('resize', scheduleViewportUpdate)
  window.visualViewport?.addEventListener('scroll', scheduleViewportUpdate)
  window.addEventListener('focusin', keepMobileViewportAnchored)
  window.addEventListener('focusout', keepMobileViewportAnchored)
  window.addEventListener(DESKTOP_NAVIGATION_EVENT, handleNavigation)

  if (nativeBridge.runtime() === 'capacitor') {
    const listener = await App.addListener('backButton', () => {
      void handleAndroidBack()
    })
    removeAndroidBackListener = () => listener.remove()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleViewportUpdate)
  window.visualViewport?.removeEventListener('resize', scheduleViewportUpdate)
  window.visualViewport?.removeEventListener('scroll', scheduleViewportUpdate)
  window.removeEventListener('focusin', keepMobileViewportAnchored)
  window.removeEventListener('focusout', keepMobileViewportAnchored)
  window.removeEventListener(DESKTOP_NAVIGATION_EVENT, handleNavigation)
  if (viewportFrame !== null) cancelAnimationFrame(viewportFrame)
  if (focusResetFrame !== null) cancelAnimationFrame(focusResetFrame)
  removeAndroidBackListener?.()
  removeAndroidBackListener = null
})
</script>

<template></template>
