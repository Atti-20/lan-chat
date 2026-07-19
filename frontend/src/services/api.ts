import type {
  AdminUser,
  AuthSession,
  ChatGroup,
  ChatMessage,
  BroadcastCreatePayload,
  BroadcastCompletePayload,
  BroadcastDetail,
  BroadcastRecipientDetail,
  BroadcastReceiver,
  BroadcastStatistics,
  BroadcastTargetUpdatePayload,
  BroadcastTargetUpdateResult,
  DeviceLogin,
  FileUpload,
  Friend,
  FriendRequest,
  GroupMember,
  AdminDiagnostics,
  DiscoveredNode,
  NodePublicInfo,
  RuntimeLogLevelFilter,
  RuntimeLogSnapshot,
  ResumableUploadSession,
  EmergencyBroadcast,
  TemporaryRoom,
  TemporaryRoomCreatePayload,
  User,
} from '../types'
import { nativeBridge } from '../platform/nativeBridge'
import {
  apiUrl,
  currentNodeApiBasePath,
  currentNodeOrigin,
  resourceUrl,
} from '../platform/nodeContext'
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
    try {
      if (nativeBridge.runtime() === 'tauri') {
        const refreshed = await nativeBridge.desktopRefresh(
          currentNodeOrigin(),
          currentNodeApiBasePath(),
        )
        if (!refreshed) return false
        writeSession(refreshed)
        return true
      }

      const response = await fetch(apiUrl('/auth/refresh'), {
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
      writeSession(result.data)
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
    response = await fetch(apiUrl(path), { ...init, headers })
  } catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') throw cause
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

async function download(path: string, fallbackName: string, retry = true): Promise<{ blob: Blob; fileName: string }> {
  const headers = new Headers()
  headers.set('X-Request-ID', `req_${crypto.randomUUID?.().replace(/-/g, '') || Date.now()}`)
  Object.entries(authHeaders()).forEach(([key, value]) => headers.set(key, value))

  let response: Response
  try {
    response = await fetch(apiUrl(path), { headers })
  } catch {
    throw new ApiError('无法连接服务器，请检查网络', 0)
  }

  if (response.status === 401 && retry) {
    if (await refreshAccessToken()) return download(path, fallbackName, false)
    clearSession()
    throw new ApiError('登录已过期，请重新登录', 401)
  }

  if (!response.ok) {
    let message = '文件导出失败'
    try {
      const result = await response.json() as ApiResult<unknown>
      message = result.msg || message
    } catch {
      // The export endpoint may return an empty 404 response before the first log line is written.
    }
    throw new ApiError(message, response.status)
  }

  if (response.headers.get('Content-Type')?.includes('application/json')) {
    const result = await response.json() as ApiResult<unknown>
    throw new ApiError(result.msg || '文件导出失败', result.code || response.status)
  }

  return {
    blob: await response.blob(),
    fileName: responseFileName(response.headers.get('Content-Disposition'), fallbackName),
  }
}

function responseFileName(contentDisposition: string | null, fallback: string): string {
  if (!contentDisposition) return fallback
  const encoded = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return fallback
    }
  }
  return contentDisposition.match(/filename="?([^";]+)"?/i)?.[1] || fallback
}

function storedFileName(rawUrl: string): string {
  const pathname = new URL(rawUrl, currentNodeOrigin()).pathname
  return decodeURIComponent(pathname.split('/').pop() || '')
}

export const api = {
  node: {
    info: () => request<NodePublicInfo>('/node/info'),
    discoveries: () => request<DiscoveredNode[]>('/node/discoveries'),
  },
  auth: {
    login: (username: string, password: string) => nativeBridge.runtime() === 'tauri'
      ? nativeBridge.desktopLogin(
        currentNodeOrigin(),
        currentNodeApiBasePath(),
        username,
        password,
      )
      : request<AuthSession>('/auth/login', {
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
    logout: () => nativeBridge.runtime() === 'tauri'
      ? nativeBridge.desktopLogout(
        currentNodeOrigin(),
        currentNodeApiBasePath(),
        readSession()?.token,
      )
      : request<void>('/auth/logout', { method: 'POST' }),
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
  rooms: {
    list: () => request<TemporaryRoom[]>('/rooms'),
    get: (roomId: number) => request<TemporaryRoom>(`/rooms/${roomId}`),
    create: (payload: TemporaryRoomCreatePayload) => request<TemporaryRoom>('/rooms', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
    join: (roomCode: string) => request<TemporaryRoom>('/rooms/join', {
      method: 'POST',
      body: JSON.stringify({ roomCode }),
    }),
    leave: (roomId: number) => request<void>(`/rooms/${roomId}/leave`, { method: 'POST' }),
  },
  broadcasts: {
    list: () => request<EmergencyBroadcast[]>('/broadcast'),
    pending: () => request<EmergencyBroadcast[]>('/broadcast/pending'),
    detail: (broadcastId: number) => request<BroadcastDetail>(`/broadcast/${broadcastId}`),
    create: (payload: BroadcastCreatePayload) => request<EmergencyBroadcast>('/broadcast', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
    cancel: (broadcastId: number) => request<EmergencyBroadcast>(
      `/broadcast/${broadcastId}/cancel`,
      { method: 'POST' },
    ),
    remove: (broadcastId: number) => request<void>(
      `/broadcast/${broadcastId}`,
      { method: 'DELETE' },
    ),
    view: (broadcastId: number) => request<BroadcastReceiver>(
      `/broadcast/${broadcastId}/view`,
      { method: 'POST' },
    ),
    confirm: (broadcastId: number, status: string) => request<BroadcastReceiver>(
      `/broadcast/${broadcastId}/confirm`,
      { method: 'POST', body: JSON.stringify({ status }) },
    ),
    complete: (broadcastId: number, payload: BroadcastCompletePayload) => request<BroadcastReceiver>(
      `/broadcast/${broadcastId}/complete`,
      { method: 'POST', body: JSON.stringify(payload) },
    ),
    stats: (broadcastId: number) => request<BroadcastStatistics>(`/broadcast/${broadcastId}/stats`),
    recipients: (broadcastId: number, bucket = 'ALL') => request<BroadcastRecipientDetail[]>(
      `/broadcast/${broadcastId}/receivers?bucket=${encodeURIComponent(bucket)}`,
    ),
    remind: (broadcastId: number, userId: number) => request<void>(
      `/broadcast/${broadcastId}/receivers/${userId}/remind`,
      { method: 'POST' },
    ),
    updateTargets: (broadcastId: number, payload: BroadcastTargetUpdatePayload) => request<BroadcastTargetUpdateResult>(
      `/broadcast/${broadcastId}/receivers`,
      { method: 'PATCH', body: JSON.stringify(payload) },
    ),
    exportExcel: (broadcastId: number) => download(
      `/broadcast/${broadcastId}/export.xlsx`,
      `broadcast-${broadcastId}.xlsx`,
    ),
  },
  chat: {
    history: (conversationId: string, limit = 50, beforeSequence?: number) => request<ChatMessage[]>(
      `/chat/history?conversationId=${encodeURIComponent(conversationId)}&limit=${limit}${
        beforeSequence ? `&beforeSequence=${beforeSequence}` : ''
      }`,
    ),
    search: (keyword: string, limit = 50) => request<ChatMessage[]>(
      `/chat/search?keyword=${encodeURIComponent(keyword)}&limit=${limit}`,
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
    uploadBroadcastImage: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return request<FileUpload>('/file/broadcast-image', { method: 'POST', body: form })
    },
    createUpload: (
      payload: {
        clientUploadId: string
        conversationId: string
        fileName: string
        fileSize: number
        fileType: string
        fileHash: string
      },
      signal?: AbortSignal,
    ) => request<ResumableUploadSession>('/file/uploads', {
      method: 'POST',
      body: JSON.stringify(payload),
      signal,
    }),
    uploadStatus: (uploadId: string, signal?: AbortSignal) =>
      request<ResumableUploadSession>(
        `/file/uploads/${encodeURIComponent(uploadId)}`,
        { signal },
      ),
    uploadPart: (
      uploadId: string,
      partNumber: number,
      sha256: string,
      data: Blob,
      signal?: AbortSignal,
    ) => request<ResumableUploadSession>(
      `/file/uploads/${encodeURIComponent(uploadId)}/parts/${partNumber}?sha256=${encodeURIComponent(sha256)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: data,
        signal,
      },
    ),
    completeUpload: (uploadId: string, signal?: AbortSignal) => request<FileUpload>(
      `/file/uploads/${encodeURIComponent(uploadId)}/complete`,
      { method: 'POST', signal },
    ),
    cancelUpload: (uploadId: string) => request<void>(
      `/file/uploads/${encodeURIComponent(uploadId)}`,
      { method: 'DELETE' },
    ),
    temporaryUrl: async (rawUrl: string) => resourceUrl(await request<string>(
      `/file/preview-url?fileName=${encodeURIComponent(storedFileName(rawUrl))}`,
      { method: 'POST' },
    )),
  },
  admin: {
    users: () => request<AdminUser[]>('/admin/users'),
    createUser: (payload: { username: string; password: string; nickname: string }) => request<void>(
      '/admin/users',
      { method: 'POST', body: JSON.stringify(payload) },
    ),
    setStatus: (userId: number, status: 0 | 1) => request<string>(
      `/admin/user/status?userId=${userId}&status=${status}`,
      { method: 'POST' },
    ),
    setMutePeriod: (userId: number, muteStart: string, muteEnd: string) => request<string>(
      `/admin/user/mute?userId=${userId}&muteStart=${encodeURIComponent(muteStart)}&muteEnd=${encodeURIComponent(muteEnd)}`,
      { method: 'POST' },
    ),
    resetPassword: (userId: number, newPassword: string) => request<void>(
      `/admin/user/${userId}/password`,
      { method: 'PUT', body: JSON.stringify({ newPassword }) },
    ),
    setBroadcastPermission: (userId: number, enabled: boolean) => request<void>(
      `/admin/user/${userId}/broadcast-permission?enabled=${enabled}`,
      { method: 'PUT' },
    ),
    deleteUser: (userId: number) => request<string>(`/admin/user/${userId}`, { method: 'DELETE' }),
    diagnostics: () => request<AdminDiagnostics>('/admin/diagnostics'),
    runtimeLogs: (options: { limit?: number; level?: RuntimeLogLevelFilter; keyword?: string } = {}) => {
      const params = new URLSearchParams({
        limit: String(options.limit ?? 300),
        level: options.level ?? 'ALL',
      })
      if (options.keyword?.trim()) params.set('keyword', options.keyword.trim())
      return request<RuntimeLogSnapshot>(`/admin/logs?${params}`)
    },
    exportRuntimeLog: () => download('/admin/logs/export', 'lan-chat-runtime.log'),
  },
}
