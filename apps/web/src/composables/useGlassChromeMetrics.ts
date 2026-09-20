import { onBeforeUnmount, onMounted, onUpdated, type Ref } from 'vue'

/** Measure actual chrome, including Dynamic Type/zoom. These are runtime CSS
 * inputs, not static tokens; no virtual keyboard height is added here. */
export function useGlassChromeMetrics(element: Ref<HTMLElement | null>, kind: 'navigation' | 'workspace'): void {
  let observer: ResizeObserver | undefined
  let frame = 0
  let disposed = false
  let owner: HTMLElement | null = null
  let lastOcclusion = ''
  const write = (node: HTMLElement, name: string, value: number) => {
    if (Number.isFinite(value)) node.style.setProperty(name, `${Math.max(0, value)}px`)
  }
  const measure = () => {
    frame = 0
    const host = element.value
    if (disposed || !host) return
    if (kind === 'workspace') {
      const top = host.querySelector<HTMLElement>('.workspace-header')?.getBoundingClientRect().height ?? 0
      const bottom = host.querySelector<HTMLElement>('.composer-wrap')?.getBoundingClientRect().height ?? 0
      write(host, '--mx-runtime-chrome-top', top)
      write(host, '--mx-runtime-chrome-bottom', bottom)
      // At extreme text sizes / keyboard-reduced heights, favor reachability
      // over glass overlap. A single flow container can then scroll the chrome.
      const height = host.getBoundingClientRect().height
      host.classList.toggle('chrome-in-flow', height > 0 && top + bottom > height - 96)
      return
    }
    const items = host.querySelector<HTMLElement>('.rail-items')
    const active = items?.querySelector<HTMLElement>('[aria-current="page"]')
    if (items && active) {
      write(host, '--mx-lens-x', active.offsetLeft)
      write(host, '--mx-lens-y', active.offsetTop)
      write(host, '--mx-lens-width', active.offsetWidth)
      write(host, '--mx-lens-height', active.offsetHeight)
    }
    owner = host.closest<HTMLElement>('.chat-shell')
    if (owner) {
      const bounds = host.getBoundingClientRect()
      const floating = getComputedStyle(host).position === 'fixed' && bounds.height > 0
      const bottom = parseFloat(getComputedStyle(host).bottom) || 0
      // The only trailing reservation lives on the scroll content, not on its
      // parent. This allows real messages to pass behind the floating material.
      lastOcclusion = `${floating ? Math.ceil(bounds.height + bottom + 12) : 0}px`
      owner.style.setProperty('--mx-runtime-nav-occlusion', lastOcclusion)
    }
  }
  const schedule = () => {
    if (!disposed && !frame) frame = requestAnimationFrame(measure)
  }
  onMounted(() => {
    const host = element.value
    if (host && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(schedule)
      observer.observe(host)
      for (const node of host.querySelectorAll('.workspace-header, .composer-wrap, .rail-items')) observer.observe(node)
    }
    window.addEventListener('resize', schedule, { passive: true })
    window.visualViewport?.addEventListener('resize', schedule, { passive: true })
    schedule()
  })
  onUpdated(schedule)
  onBeforeUnmount(() => {
    disposed = true
    cancelAnimationFrame(frame)
    observer?.disconnect()
    window.removeEventListener('resize', schedule)
    window.visualViewport?.removeEventListener('resize', schedule)
    if (owner?.style.getPropertyValue('--mx-runtime-nav-occlusion') === lastOcclusion) {
      owner.style.removeProperty('--mx-runtime-nav-occlusion')
    }
  })
}
