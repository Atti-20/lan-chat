import { readonly, ref, shallowRef } from 'vue'
import { ApiError, api } from '../services/api'
import type { AdminUser } from '../types'
import { useToast } from './useToast'

export function useAdmin() {
  const toast = useToast()
  const users = ref<AdminUser[]>([])
  const loading = shallowRef(false)
  const loaded = shallowRef(false)
  const creating = shallowRef(false)
  const createdUsername = shallowRef<string | null>(null)
  const busyUserId = shallowRef<number | null>(null)

  async function loadUsers(): Promise<void> {
    loading.value = true
    try {
      users.value = await api.admin.users()
      loaded.value = true
    } catch (cause) {
      toast.push(errorMessage(cause, '管理数据加载失败'), 'danger')
    } finally {
      loading.value = false
    }
  }

  async function setUserStatus(payload: { userId: number; status: 0 | 1 }): Promise<void> {
    await runUserAction(payload.userId, async () => {
      await api.admin.setStatus(payload.userId, payload.status)
      toast.push(payload.status === 0 ? '账号已封禁' : '账号已恢复', 'success')
    })
  }

  async function createUser(payload: { username: string; password: string; nickname: string }): Promise<void> {
    creating.value = true
    createdUsername.value = null
    try {
      await api.admin.createUser(payload)
      toast.push(`账号 ${payload.username} 已创建`, 'success')
      await loadUsers()
      createdUsername.value = payload.username
    } catch (cause) {
      toast.push(errorMessage(cause, '账号创建失败'), 'danger')
    } finally {
      creating.value = false
    }
  }

  async function setMutePeriod(payload: { userId: number; muteStart: string; muteEnd: string }): Promise<void> {
    await runUserAction(payload.userId, async () => {
      await api.admin.setMutePeriod(payload.userId, payload.muteStart, payload.muteEnd)
      toast.push('禁言时段已更新', 'success')
    })
  }

  async function deleteUser(userId: number): Promise<void> {
    await runUserAction(userId, async () => {
      await api.admin.deleteUser(userId)
      toast.push('用户已删除', 'success')
    })
  }

  async function runUserAction(userId: number, action: () => Promise<void>): Promise<void> {
    busyUserId.value = userId
    try {
      await action()
      await loadUsers()
    } catch (cause) {
      toast.push(errorMessage(cause, '管理操作失败'), 'danger')
    } finally {
      busyUserId.value = null
    }
  }

  function errorMessage(cause: unknown, fallback: string): string {
    return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback
  }

  return {
    users: readonly(users),
    loading: readonly(loading),
    loaded: readonly(loaded),
    creating: readonly(creating),
    createdUsername: readonly(createdUsername),
    busyUserId: readonly(busyUserId),
    loadUsers,
    createUser,
    setUserStatus,
    setMutePeriod,
    deleteUser,
  }
}
