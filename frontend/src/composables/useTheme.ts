import { readonly, shallowRef } from 'vue'
import { readTheme, writeTheme, type ThemeMode } from '../utils/storage'

interface ViewTransitionLike {
  finished: Promise<void>
}

interface ViewTransitionDocument {
  startViewTransition?: (update: () => void) => ViewTransitionLike
}

function resolveInitial(): ThemeMode {
  const stored = readTheme()
  if (stored) return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const mode = shallowRef<ThemeMode>(resolveInitial())
let activeOverlay: HTMLDivElement | null = null

function applyMode(next: ThemeMode): void {
  document.documentElement.setAttribute('data-theme', next)
}

applyMode(mode.value)

/* ── helpers ── */

function maxCoverRadius(x: number, y: number): number {
  return Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y),
  )
}

function isMicrosoftEdge(): boolean {
  // Edge exposes startViewTransition, but its view-transition pseudo-elements
  // can render the clip-path reveal incorrectly. Keep its theme switch
  // immediate, including Android and iOS Edge user agents.
  return /\bEdg(?:A|iOS)?\//.test(navigator.userAgent)
}

/* ── Native view transition path ── */

function revealWithViewTransition(x: number, y: number, next: ThemeMode): void {
  const root = document.documentElement
  const transitionDocument = document as ViewTransitionDocument
  const endRadius = maxCoverRadius(x, y)

  root.style.setProperty('--theme-reveal-x', `${x}px`)
  root.style.setProperty('--theme-reveal-y', `${y}px`)
  root.style.setProperty('--theme-reveal-radius', `${endRadius}px`)
  activeOverlay?.remove()
  activeOverlay = null

  try {
    const transition = transitionDocument.startViewTransition!(() => {
      // The document theme changes inside the update callback, before the
      // browser starts animating the captured new-theme snapshot.
      mode.value = next
      applyMode(next)
      writeTheme(next)
    })
    const clearRevealProperties = () => {
      root.style.removeProperty('--theme-reveal-x')
      root.style.removeProperty('--theme-reveal-y')
      root.style.removeProperty('--theme-reveal-radius')
    }
    transition.finished.then(clearRevealProperties, clearRevealProperties)
  } catch (error) {
    root.style.removeProperty('--theme-reveal-x')
    root.style.removeProperty('--theme-reveal-y')
    root.style.removeProperty('--theme-reveal-radius')
    throw error
  }
}

/* ── CSS overlay fallback path ── */

function revealWithOverlay(x: number, y: number, next: ThemeMode): void {
  const endRadius = maxCoverRadius(x, y)

  // Apply the new variables immediately. The overlay is only a visual reveal
  // layer, so it expands from the click point without delaying the state.
  mode.value = next
  applyMode(next)
  writeTheme(next)

  const targetStyles = getComputedStyle(document.documentElement)
  const targetBg = targetStyles
    .getPropertyValue('--canvas').trim() || (next === 'dark' ? '#0f0f10' : '#edf0f4')
  const targetBodyBackground = getComputedStyle(document.body).backgroundImage
  activeOverlay?.remove()

  const overlay = document.createElement('div')
  const initClip = `circle(0px at ${x}px ${y}px)`
  const endClip = `circle(${endRadius}px at ${x}px ${y}px)`

  overlay.style.cssText = [
    'position:fixed',
    'inset:0',
    'z-index:99999',
    `background:${targetBodyBackground && targetBodyBackground !== 'none' ? targetBodyBackground : targetBg}`,
    'opacity:.86',
    'pointer-events:none',
    `clip-path:${initClip}`,
    `-webkit-clip-path:${initClip}`,
    'will-change:clip-path,backdrop-filter',
    'backdrop-filter:blur(18px) saturate(140%)',
    '-webkit-backdrop-filter:blur(18px) saturate(140%)',
  ].join(';')

  document.body.appendChild(overlay)
  activeOverlay = overlay

  let done = false
  let timer = 0
  const cleanup = () => {
    if (done) return
    done = true
    window.clearTimeout(timer)
    if (activeOverlay === overlay) activeOverlay = null
    overlay.remove()
  }

  overlay.addEventListener('transitionend', (event) => {
    if (event.propertyName === 'clip-path' || event.propertyName === '-webkit-clip-path') cleanup()
  })
  timer = window.setTimeout(cleanup, 560)
  requestAnimationFrame(() => {
    if (done) return
    overlay.style.transition = 'clip-path 500ms cubic-bezier(0.4,0,0.2,1),-webkit-clip-path 500ms cubic-bezier(0.4,0,0.2,1)'
    overlay.style.clipPath = endClip
    ;(overlay.style as any).webkitClipPath = endClip
  })
}

/* ── Public API ── */

export function useTheme() {
  function toggle(): void {
    const next: ThemeMode = mode.value === 'dark' ? 'light' : 'dark'
    mode.value = next
    applyMode(next)
    writeTheme(next)
  }

  function toggleWithReveal(event: MouseEvent): void {
    const next: ThemeMode = mode.value === 'dark' ? 'light' : 'dark'

    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      mode.value = next
      applyMode(next)
      writeTheme(next)
      return
    }

    // Avoid Edge's inconsistent view-transition compositing altogether.
    if (isMicrosoftEdge()) {
      mode.value = next
      applyMode(next)
      writeTheme(next)
      return
    }

    const x = event.clientX
    const y = event.clientY

    const transitionDocument = document as ViewTransitionDocument
    if (typeof transitionDocument.startViewTransition === 'function') {
      try {
        revealWithViewTransition(x, y, next)
        return
      } catch {
        // Fall back to the CSS circle when the browser exposes an incomplete API.
      }
    }
    revealWithOverlay(x, y, next)
  }

  return {
    mode: readonly(mode),
    toggle,
    toggleWithReveal,
  }
}
