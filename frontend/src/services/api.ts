import type {
  AuthSession,
  ChatGroup,
  ChatMessage,
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
    if (!session?.refreshToken) return false

    try {
      const response = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          refreshToken: session.refreshToken,
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

async function fetchFileBlob(rawUrl: string, retry = true): Promise<Blob> {
  const fileName = rawUrl.split('/').pop() || ''
  const url = rawUrl.startsWith('/file/')
    ? `/api/v1/file/content/${encodeURIComponent(fileName)}`
    : rawUrl
  const response = await fetch(url, { headers: authHeaders() })
  if (response.status === 401 && retry && await refreshAccessToken()) {
    return fetchFileBlob(rawUrl, false)
  }
  if (!response.ok) throw new ApiError('文件不可用或你没有访问权限', response.status)
  return response.blob()
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
  },
  user: {
    me: () => request<User>('/user/info'),
    byId: (id: number) => request<User>(`/user/${id}`),
    search: (keyword: string) => request<User[]>(`/user/search?keyword=${encodeURIComponent(keyword)}`),
    updateProfile: (payload: { nickname?: string; avatar?: string }) => request<User>('/user/profile', {
      method: 'PUT',
      body: JSON.stringify(payload),
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
  },
  groups: {
    list: () => request<ChatGroup[]>('/group/my'),
    byId: (groupId: number) => request<ChatGroup>(`/group/${groupId}`),
    members: (groupId: number) => request<GroupMember[]>(`/group/${groupId}/members`),
    create: (groupName: string, memberIds: number[]) => request<ChatGroup>('/group', {
      method: 'POST',
      body: JSON.stringify({ groupName, memberIds }),
    }),
  },
  chat: {
    privateHistory: (targetId: number, limit = 50) => request<ChatMessage[]>(
      `/chat/history/private?targetId=${targetId}&limit=${limit}`,
    ),
    groupHistory: (groupId: number, limit = 50) => request<ChatMessage[]>(
      `/chat/history/group?groupId=${groupId}&limit=${limit}`,
    ),
    markRead: (fromUserId: number) => request<void>(`/chat/read?fromUserId=${fromUserId}`, { method: 'PUT' }),
    recall: (messageId: string) => request<void>(`/chat/recall?messageId=${encodeURIComponent(messageId)}`, {
      method: 'POST',
    }),
    burn: (messageId: string) => request<void>(`/chat/burn?messageId=${encodeURIComponent(messageId)}`, {
      method: 'POST',
    }),
  },
  files: {
    upload: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return request<FileUpload>('/file/upload', { method: 'POST', body: form })
    },
    blob: fetchFileBlob,
  },
}
