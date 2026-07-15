import { computed, readonly, shallowRef } from 'vue'
import { api, ApiError } from '../services/api'
import { clearLocalChatDatabase } from '../services/localChatDb'
import type { AuthSession, User } from '../types'
import {
  clearCacheOwner,
  clearSession,
  readCacheOwner,
  readSession,
  writeCacheOwner,
  writeLastUsername,
  writeSession,
} from '../utils/storage'

const session = shallowRef<AuthSession | null>(readSession())
const currentUser = shallowRef<User | null>(null)
const loading = shallowRef(false)

export function useAuth() {
  const isAuthenticated = computed(() => Boolean(session.value?.token))

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      const data = await api.auth.login(username, password)
      await prepareLocalCache(data.userId)
      session.value = data
      currentUser.value = {
        id: data.userId,
        userId: data.userId,
        username: data.username,
        nickname: data.nickname,
        avatar: data.avatar,
      }
      writeSession(data)
      writeLastUsername(username)
    } finally {
      loading.value = false
    }
  }

  async function register(username: string, password: string, nickname: string): Promise<void> {
    loading.value = true
    try {
      await api.auth.register(username, password, nickname)
      await login(username, password)
    } finally {
      loading.value = false
    }
  }

  async function hydrate(): Promise<User | null> {
    if (!session.value) {
      const refreshed = await api.auth.refreshSession()
      if (!refreshed) return null
      session.value = readSession()
    }
    if (session.value) await prepareLocalCache(session.value.userId)
    try {
      const user = await api.user.me()
      currentUser.value = { ...user, userId: user.id }
      return currentUser.value
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 0 && session.value) {
        currentUser.value = {
          id: session.value.userId,
          userId: session.value.userId,
          username: session.value.username,
          nickname: session.value.nickname,
          avatar: session.value.avatar,
        }
        return currentUser.value
      }
      clearSession()
      // 普通认证过期保留该用户的本地发件箱；重新登录其他账号时会按 owner 清理。
      // 明确退出和 WebSocket FORCE_LOGOUT 仍会清除本地数据。
      session.value = null
      currentUser.value = null
      return null
    }
  }

  async function prepareLocalCache(userId: number): Promise<void> {
    const owner = readCacheOwner()
    if (owner != null && owner !== userId) {
      try {
        await clearLocalChatDatabase()
      } catch {
        throw new Error('无法安全切换本地账号，请刷新页面后重试')
      }
    }
    writeCacheOwner(userId)
  }

  async function updateProfile(payload: { nickname?: string; avatar?: string }): Promise<User> {
    const user = await api.user.updateProfile(payload)
    currentUser.value = { ...user, userId: user.id }
    if (session.value) {
      session.value = {
        ...session.value,
        nickname: user.nickname,
        avatar: user.avatar,
      }
      writeSession(session.value)
    }
    return user
  }

  async function logout(): Promise<void> {
    try {
      await api.auth.logout()
    } finally {
      clearSession()
      await clearLocalChatDatabase()
        .then(() => clearCacheOwner())
        .catch(() => undefined)
      session.value = null
      currentUser.value = null
    }
  }

  return {
    session: readonly(session),
    currentUser: readonly(currentUser),
    loading: readonly(loading),
    isAuthenticated,
    login,
    register,
    hydrate,
    updateProfile,
    logout,
  }
}
