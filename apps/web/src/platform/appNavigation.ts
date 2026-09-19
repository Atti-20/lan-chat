export type AppRoute = '/' | '/chat' | '/welcome'

export function appRoute(route: AppRoute): string {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  if (!base || base === '.') return route
  return route === '/' ? `${base}/` : `${base}${route}`
}

export function navigateToApp(route: AppRoute, replace = false): void {
  const target = appRoute(route)
  if (replace) window.location.replace(target)
  else window.location.assign(target)
}
