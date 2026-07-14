import { computed, readonly, ref } from 'vue'
import { api } from '../services/api'
import type { AuthSession, User } from '../types'
import { clearSession, readSession, writeLastUsername, writeSession } from '../utils/storage'

const session = ref<AuthSession | null>(readSession())
const currentUser = ref<User | null>(null)
const loading = ref(false)

export function useAuth() {
  const isAuthenticated = computed(() => Boolean(session.value?.token))

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      const data = await api.auth.login(username, password)
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
    if (!session.value) return null
    try {
      const user = await api.user.me()
      currentUser.value = { ...user, userId: user.id }
      return currentUser.value
    } catch {
      clearSession()
      session.value = null
      currentUser.value = null
      return null
    }
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
