import { readonly, shallowRef } from 'vue'
import meshxDark from '../assets/meshx-dark.svg'
import meshxLight from '../assets/meshx-light.svg'
import { readTheme, writeTheme, type ThemeMode } from '../utils/storage'

function resolveInitial(): ThemeMode {
  const stored = readTheme()
  if (stored) return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const mode = shallowRef<ThemeMode>(resolveInitial())

function applyMode(next: ThemeMode): void {
  document.documentElement.setAttribute('data-theme', next)
  const favicon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]')
  // Full icon assets intentionally follow the supplied theme mapping:
  // MeshX_light for dark mode and MeshX_dark for light mode.
  if (favicon) favicon.href = next === 'dark' ? meshxLight : meshxDark
  const themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (themeColor) themeColor.content = next === 'dark' ? '#0f172a' : '#ffffff'
}

applyMode(mode.value)

function setMode(next: ThemeMode): void {
  mode.value = next
  applyMode(next)
  writeTheme(next)
}

export function useTheme() {
  function toggle(): void {
    const next: ThemeMode = mode.value === 'dark' ? 'light' : 'dark'
    setMode(next)
  }

  function toggleWithReveal(): void {
    toggle()
  }

  return {
    mode: readonly(mode),
    toggle,
    toggleWithReveal,
  }
}
