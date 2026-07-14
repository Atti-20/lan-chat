import { readonly, ref } from 'vue'
import { readTheme, writeTheme, type ThemeMode } from '../utils/storage'

function resolveInitial(): ThemeMode {
  const stored = readTheme()
  if (stored) return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const mode = ref<ThemeMode>(resolveInitial())

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

/* ── View Transition path ── */

function revealWithViewTransition(x: number, y: number, next: ThemeMode): void {
  const endRadius = maxCoverRadius(x, y)

  const transition = (document as any).startViewTransition(() => {
    mode.value = next
    applyMode(next)
    writeTheme(next)
  })

  transition.ready.then(() => {
    document.documentElement.animate(
      {
        clipPath: [
          `circle(0px at ${x}px ${y}px)`,
          `circle(${endRadius}px at ${x}px ${y}px)`,
        ],
      },
      {
        duration: 500,
        easing: 'cubic-bezier(0.4, 0, 0.2, 1)',
        pseudoElement: '::view-transition-new(root)',
      },
    )
  })
}

/* ── Overlay fallback path ── */

function revealWithOverlay(x: number, y: number, next: ThemeMode): void {
  const endRadius = maxCoverRadius(x, y)

  // Temporarily apply target theme to read its canvas color
  applyMode(next)
  const targetBg = getComputedStyle(document.documentElement)
    .getPropertyValue('--canvas').trim() || (next === 'dark' ? '#0f0f10' : '#edf0f4')
  // Revert to current
  applyMode(mode.value)

  const overlay = document.createElement('div')
  const initClip = `circle(0px at ${x}px ${y}px)`
  const endClip = `circle(${endRadius}px at ${x}px ${y}px)`

  overlay.style.cssText = [
    'position:fixed',
    'inset:0',
    'z-index:99999',
    `background:${targetBg}`,
    'pointer-events:none',
    `clip-path:${initClip}`,
    `-webkit-clip-path:${initClip}`,
    'will-change:clip-path',
  ].join(';')

  document.body.appendChild(overlay)
  overlay.getBoundingClientRect() // force reflow

  overlay.style.transition = 'clip-path 500ms cubic-bezier(0.4,0,0.2,1),-webkit-clip-path 500ms cubic-bezier(0.4,0,0.2,1)'
  overlay.style.clipPath = endClip
  ;(overlay.style as any).webkitClipPath = endClip

  let done = false
  const cleanup = () => {
    if (done) return
    done = true
    mode.value = next
    applyMode(next)
    writeTheme(next)
    overlay.remove()
  }

  overlay.addEventListener('transitionend', cleanup, { once: true })
  setTimeout(cleanup, 560)
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

    const x = event.clientX
    const y = event.clientY

    if ((document as any).startViewTransition) {
      revealWithViewTransition(x, y, next)
    } else {
      revealWithOverlay(x, y, next)
    }
  }

  return {
    mode: readonly(mode),
    toggle,
    toggleWithReveal,
  }
}
