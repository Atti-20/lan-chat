import type { AuthSession } from '../types'

const SESSION_KEY = 'lanchat_session_v2'
const LAST_USERNAME_KEY = 'lanchat_last_username'
const THEME_KEY = 'lanchat_theme'

export function readSession(): AuthSession | null {
  try {
    const value = localStorage.getItem(SESSION_KEY)
    return value ? JSON.parse(value) as AuthSession : null
  } catch {
    return null
  }
}

export function writeSession(session: AuthSession): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY)
  // 清理旧版前端遗留的认证信息，避免两个客户端状态打架。
  ;['token', 'refreshToken', 'userId', 'userInfo', 'expiresIn']
    .forEach((key) => localStorage.removeItem(`lanchat_${key}`))
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
