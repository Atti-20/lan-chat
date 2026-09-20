import type { AuthSession } from '../types'
import { currentNodeKey } from '../platform/nodeContext'

const SESSION_KEY = 'lanchat_session_v2'
const LAST_USERNAME_KEY = 'lanchat_last_username'
const THEME_KEY = 'lanchat_theme'
const CACHE_OWNER_KEY = 'lanchat_cache_owner'

export function readSession(): AuthSession | null {
  try {
    const value = sessionStorage.getItem(SESSION_KEY)
    return value ? JSON.parse(value) as AuthSession : null
  } catch {
    return null
  }
}

export function writeSession(session: AuthSession): void {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  sessionStorage.removeItem(SESSION_KEY)
  localStorage.removeItem(SESSION_KEY)
  // 清理旧版前端遗留的认证信息，避免两个客户端状态打架。
  ;['token', 'refreshToken', 'userId', 'userInfo', 'expiresIn']
    .forEach((key) => {
      localStorage.removeItem(`lanchat_${key}`)
      sessionStorage.removeItem(`lanchat_${key}`)
    })
}

export function readLastUsername(): string {
  return localStorage.getItem(LAST_USERNAME_KEY) || ''
}

export function writeLastUsername(username: string): void {
  localStorage.setItem(LAST_USERNAME_KEY, username)
}

export type ThemeMode = 'light' | 'dark'

export function readTheme(): ThemeMode | null {
  const value = localStorage.getItem(THEME_KEY)
  if (value === 'light' || value === 'dark') return value
  return null
}

export function writeTheme(mode: ThemeMode): void {
  localStorage.setItem(THEME_KEY, mode)
}

export function cacheOwnerKey(userId: number): string {
  return `${currentNodeKey()}::${userId}`
}

export function readCacheOwner(): string | null {
  return localStorage.getItem(CACHE_OWNER_KEY)
}

export function writeCacheOwner(userId: number): void {
  localStorage.setItem(CACHE_OWNER_KEY, cacheOwnerKey(userId))
}

export function clearCacheOwner(): void {
  localStorage.removeItem(CACHE_OWNER_KEY)
}
