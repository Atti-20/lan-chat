import type { AuthSession } from '../types'

const SESSION_KEY = 'lanchat_session_v2'

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
