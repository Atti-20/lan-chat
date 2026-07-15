import type {
  AdminUser,
  AuthSession,
  ChatGroup,
  ChatMessage,
  DeviceLogin,
  FileUpload,
  Friend,
  FriendRequest,
  GroupMember,
  User,
} from '../types'
import { clearSession, readSession, writeSession } from '../utils/storage'

interface ApiResult<T> {
  code: number
  msg: string
  data: T
  requestId?: string
}

export class ApiError extends Error {
  constructor(message: string, public readonly code: number) {
    super(message)
  }
}

let refreshPromise: Promise<boolean> | null = null

function authHeaders(): Record<string, string> {
  const token = readSession()?.token
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    const session = readSession()

    try {
      const response = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          deviceType: 'web',
          deviceName: navigator.userAgent.slice(0, 100),
        }),
      })
      const result = await response.json() as ApiResult<AuthSession>
      if (!response.ok || result.code !== 200 || !result.data) return false
      writeSession({ ...session, ...result.data })
      return true
    } catch {
      return false
    }
  })()

  try {
    return await refreshPromise
  } finally {
    refreshPromise = null
  }
}

async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const isFormData = init.body instanceof FormData
  const headers = new Headers(init.headers)
  headers.set('X-Request-ID', `req_${crypto.randomUUID?.().replace(/-/g, '') || Date.now()}`)
  Object.entries(authHeaders()).forEach(([key, value]) => headers.set(key, value))
  if (!isFormData && init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接服务器，请检查网络', 0)
  }

  if (response.status === 401 && retry) {
    if (await refreshAccessToken()) return request<T>(path, init, false)
    clearSession()
    throw new ApiError('登录已过期，请重新登录', 401)
  }

  let result: ApiResult<T>
  try {
    result = await response.json() as ApiResult<T>
  } catch {
    throw new ApiError('服务器返回了无法识别的数据', response.status)
  }

  if (!response.ok || result.code !== 200) {
    throw new ApiError(result.msg || '操作失败', result.code || response.status)
  }
  return result.data
}

function storedFileName(rawUrl: string): string {
  const pathname = new URL(rawUrl, window.location.origin).pathname
  return decodeURIComponent(pathname.split('/').pop() || '')
}

export const api = {
  auth: {
    login: (username: string, password: string) => request<AuthSession>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        username,
        password,
        deviceType: 'web',
        deviceName: navigator.userAgent.slice(0, 100),
      }),
    }),
    register: (username: string, password: string, nickname: string) => request<void>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, nickname }),
    }),
    logout: () => request<void>('/auth/logout', { method: 'POST' }),
    refreshSession: refreshAccessToken,
  },
  user: {
    me: () => request<User>('/user/info'),
    byId: (id: number) => request<User>(`/user/${id}`),
    search: (keyword: string) => request<User[]>(`/user/search?keyword=${encodeURIComponent(keyword)}`),
    updateProfile: (payload: { nickname?: string; avatar?: string }) => request<User>('/user/profile', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
    devices: () => request<DeviceLogin[]>('/user/devices'),
    logoutDevice: (deviceId: number) => request<void>(`/user/devices/${deviceId}`, { method: 'DELETE' }),
    changePassword: (oldPassword: string, newPassword: string) => request<void>('/user/password', {
      method: 'PUT',
      body: JSON.stringify({ oldPassword, newPassword }),
    }),
  },
  friends: {
    list: () => request<Friend[]>('/friend/list'),
    requests: () => request<FriendRequest[]>('/friend/requests'),
    sendRequest: (toUserId: number, message: string) => request<void>('/friend/request', {
      method: 'POST',
      body: JSON.stringify({ toUserId, message }),
    }),
    handleRequest: (requestId: number, accept: boolean) => request<void>('/friend/handle', {
      method: 'POST',
      body: JSON.stringify({ requestId, accept }),
    }),
    delete: (friendId: number) => request<void>(`/friend/${friendId}`, { method: 'DELETE' }),
    togglePin: (friendId: number) => request<void>(`/friend/${friendId}/pin`, { method: 'PUT' }),
    toggleMute: (friendId: number) => request<void>(`/friend/${friendId}/mute`, { method: 'PUT' }),
    setRemark: (friendId: number, remark: string) => request<void>(
      `/friend/${friendId}/remark?remark=${encodeURIComponent(remark)}`,
      { method: 'PUT' },
    ),
  },
  groups: {
    list: () => request<ChatGroup[]>('/group/my'),
    members: (groupId: number) => request<GroupMember[]>(`/group/${groupId}/members`),
    create: (groupName: string, memberIds: number[]) => request<ChatGroup>('/group', {
      method: 'POST',
      body: JSON.stringify({ groupName, memberIds }),
    }),
  },
  chat: {
    history: (conversationId: string, limit = 50, beforeSequence?: number) => request<ChatMessage[]>(
      `/chat/history?conversationId=${encodeURIComponent(conversationId)}&limit=${limit}${
        beforeSequence ? `&beforeSequence=${beforeSequence}` : ''
      }`,
    ),
  },
  files: {
    upload: (file: File, conversationId: string) => {
      const form = new FormData()
      form.append('file', file)
      return request<FileUpload>(
        `/file/upload?conversationId=${encodeURIComponent(conversationId)}`,
        { method: 'POST', body: form },
      )
    },
    uploadAvatar: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return request<FileUpload>('/file/avatar', { method: 'POST', body: form })
    },
    temporaryUrl: (rawUrl: string) => request<string>(
      `/file/preview-url?fileName=${encodeURIComponent(storedFileName(rawUrl))}`,
      { method: 'POST' },
    ),
  },
  admin: {
    users: () => request<AdminUser[]>('/admin/users'),
    setStatus: (userId: number, status: 0 | 1) => request<string>(
      `/admin/user/status?userId=${userId}&status=${status}`,
      { method: 'POST' },
    ),
    setMutePeriod: (userId: number, muteStart: string, muteEnd: string) => request<string>(
      `/admin/user/mute?userId=${userId}&muteStart=${encodeURIComponent(muteStart)}&muteEnd=${encodeURIComponent(muteEnd)}`,
      { method: 'POST' },
    ),
    deleteUser: (userId: number) => request<string>(`/admin/user/${userId}`, { method: 'DELETE' }),
  },
}
