export interface ApiResult<T> {
  code: number
  msg: string
  data: T
  requestId?: string
}

export interface AuthSession {
  userId: number
  username: string
  nickname: string
  avatar?: string
  token: string
  expiresIn: number
}

export interface FriendRequest {
  id: number
  fromUserId: number
  toUserId: number
}

export interface ChatMessage {
  messageId: string
  clientMsgId: string
  conversationId: string
  sequence: number
  content: string
}

export interface ChatGroup {
  id: number
  groupName: string
  ownerId: number
}

export interface ResumableUpload {
  uploadId: string
  status: string
  chunkSize: number
  totalParts: number
  uploadedParts: number[]
  completedFile?: FileUpload | null
}

export interface FileUpload {
  id: number
  url: string
  previewUrl?: string
  thumbnailUrl?: string
  originalName: string
  fileName: string
  fileSize: number
  fileType: string
  fileHash: string
  instantUpload: boolean
}

export interface Broadcast {
  id: number
  senderId: number
  title: string
  status: string
}

export interface BroadcastReceiver {
  broadcastId: number
  userId: number
  viewedAt?: string | null
  confirmStatus: string
  confirmedAt?: string | null
}

export interface BroadcastStats {
  broadcastId: number
  targetCount: number
  deliveredCount: number
  viewedCount: number
  confirmedCount: number
  unconfirmedCount: number
  executedCount: number
  needSupportCount: number
  confirmationCounts: Record<string, number>
}

type RequestOptions = {
  method?: string
  token?: string
  body?: unknown
  rawBody?: Uint8Array
  formData?: FormData
  headers?: Record<string, string>
}

interface StoredCookie {
  value: string
  path: string
}

function normalizedBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}

function cookiePathMatches(requestPath: string, cookiePath: string): boolean {
  return requestPath === cookiePath
    || requestPath.startsWith(cookiePath.endsWith('/') ? cookiePath : `${cookiePath}/`)
}

/**
 * A deliberately small, origin-scoped cookie jar for Node's fetch. The E2E
 * suite only needs host-only cookies, but it still honours Path and Max-Age so
 * auth refresh/logout exercise the same HttpOnly-cookie contract as browsers.
 */
export class ApiClient {
  readonly baseUrl: string
  private readonly cookies = new Map<string, StoredCookie>()

  constructor(baseUrl: string) {
    this.baseUrl = normalizedBaseUrl(baseUrl)
  }

  hasCookie(name: string): boolean {
    return this.cookies.has(name)
  }

  cookieNames(): string[] {
    return [...this.cookies.keys()].sort()
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.requestRaw(path, options)
    let result: ApiResult<T>
    try {
      result = await response.json() as ApiResult<T>
    } catch {
      throw new Error(
        `${options.method || 'GET'} ${path} returned non-JSON HTTP ${response.status}`,
      )
    }
    if (!response.ok || result.code !== 200) {
      throw new Error(
        `${options.method || 'GET'} ${path} failed: `
        + `${response.status}/${result.code} ${result.msg}`
        + `${result.requestId ? ` requestId=${result.requestId}` : ''}`,
      )
    }
    return result.data
  }

  async requestRaw(path: string, options: RequestOptions = {}): Promise<Response> {
    if (!path.startsWith('/')) throw new Error(`API path must start with /: ${path}`)
    const suppliedBodies = [
      options.body !== undefined,
      options.rawBody !== undefined,
      options.formData !== undefined,
    ].filter(Boolean).length
    if (suppliedBodies > 1) {
      throw new Error('body, rawBody, and formData are mutually exclusive')
    }

    const headers = new Headers(options.headers)
    if (options.body !== undefined && options.formData === undefined && !headers.has('content-type')) {
      headers.set('content-type', 'application/json')
    }
    if (options.token) headers.set('authorization', `Bearer ${options.token}`)

    const requestPath = `/api/v1${path}`
    const cookieHeader = [...this.cookies.entries()]
      .filter(([, cookie]) => cookiePathMatches(requestPath, cookie.path))
      .map(([name, cookie]) => `${name}=${cookie.value}`)
      .join('; ')
    if (cookieHeader) headers.set('cookie', cookieHeader)

    const rawBody = options.rawBody
      ? options.rawBody.buffer.slice(
        options.rawBody.byteOffset,
        options.rawBody.byteOffset + options.rawBody.byteLength,
      ) as ArrayBuffer
      : undefined
    const response = await fetch(`${this.baseUrl}${requestPath}`, {
      method: options.method || 'GET',
      headers,
      redirect: 'manual',
      body: options.formData
        ?? (options.rawBody === undefined
          ? options.body === undefined ? undefined : JSON.stringify(options.body)
          : rawBody),
    })
    this.captureResponseCookies(response.headers)
    return response
  }

  register(username: string, password: string, nickname: string): Promise<void> {
    return this.request('/auth/register', {
      method: 'POST',
      body: { username, password, nickname },
    })
  }

  login(
    username: string,
    password: string,
    deviceName = 'LANChat E2E',
    deviceType = 'web',
  ): Promise<AuthSession> {
    return this.request('/auth/login', {
      method: 'POST',
      body: { username, password, deviceType, deviceName },
    })
  }

  refresh(deviceName = 'LANChat E2E'): Promise<AuthSession> {
    return this.request('/auth/refresh', {
      method: 'POST',
      body: { deviceType: 'web', deviceName },
    })
  }

  logout(token: string): Promise<void> {
    return this.request('/auth/logout', { method: 'POST', token })
  }

  me(token: string): Promise<{ id: number; username: string; nickname: string }> {
    return this.request('/user/info', { token })
  }

  updateProfile(
    token: string,
    input: { nickname?: string; avatar?: string },
  ): Promise<{ id: number; username: string; nickname: string; avatar?: string }> {
    return this.request('/user/profile', {
      method: 'PUT',
      token,
      body: input,
    })
  }

  archiveUser(token: string, userId: number): Promise<void> {
    return this.request(`/admin/user/${userId}`, {
      method: 'DELETE',
      token,
    })
  }

  sendFriendRequest(token: string, toUserId: number): Promise<void> {
    return this.request('/friend/request', {
      method: 'POST',
      token,
      body: { toUserId, message: 'E2E friendship setup' },
    })
  }

  friendRequests(token: string): Promise<FriendRequest[]> {
    return this.request('/friend/requests', { token })
  }

  acceptFriendRequest(token: string, requestId: number): Promise<void> {
    return this.request('/friend/handle', {
      method: 'POST',
      token,
      body: { requestId, accept: true },
    })
  }

  history(token: string, conversationId: string): Promise<ChatMessage[]> {
    const query = new URLSearchParams({ conversationId, limit: '100' })
    return this.request(`/chat/history?${query}`, { token })
  }

  createGroup(
    token: string,
    groupName: string,
    memberIds: number[],
  ): Promise<ChatGroup> {
    return this.request('/group', {
      method: 'POST',
      token,
      body: { groupName, memberIds, announcement: 'E2E group' },
    })
  }

  initializeUpload(
    token: string,
    input: {
      clientUploadId: string
      conversationId: string
      fileName: string
      fileSize: number
      fileType: string
      fileHash: string
    },
  ): Promise<ResumableUpload> {
    return this.request('/file/uploads', { method: 'POST', token, body: input })
  }

  uploadPart(
    token: string,
    uploadId: string,
    partNumber: number,
    sha256: string,
    bytes: Uint8Array,
  ): Promise<ResumableUpload> {
    const query = new URLSearchParams({ sha256 })
    return this.request(`/file/uploads/${uploadId}/parts/${partNumber}?${query}`, {
      method: 'PUT',
      token,
      rawBody: bytes,
      headers: { 'content-type': 'application/octet-stream' },
    })
  }

  completeUpload(token: string, uploadId: string): Promise<FileUpload> {
    return this.request(`/file/uploads/${uploadId}/complete`, {
      method: 'POST',
      token,
    })
  }

  uploadAvatar(
    token: string,
    fileName: string,
    mimeType: string,
    bytes: Uint8Array,
  ): Promise<FileUpload> {
    const formData = new FormData()
    const body = bytes.buffer.slice(
      bytes.byteOffset,
      bytes.byteOffset + bytes.byteLength,
    ) as ArrayBuffer
    formData.append('file', new Blob([body], { type: mimeType }), fileName)
    return this.request('/file/avatar', {
      method: 'POST',
      token,
      formData,
    })
  }

  previewUrl(token: string, fileName: string): Promise<string> {
    const query = new URLSearchParams({ fileName })
    return this.request(`/file/preview-url?${query}`, { method: 'POST', token })
  }

  async downloadSigned(previewUrl: string): Promise<Response> {
    return fetch(new URL(previewUrl, this.baseUrl), { redirect: 'manual' })
  }

  createBroadcast(
    token: string,
    input: {
      title: string
      content: string
      scopeType: 'ALL' | 'USERS'
      receiverIds?: number[]
      confirmationRequired: boolean
      confirmationOptions?: string[]
    },
  ): Promise<Broadcast> {
    return this.request('/broadcast', {
      method: 'POST',
      token,
      body: {
        priority: 'NORMAL',
        bypassMute: false,
        repeatReminder: false,
        requireImageProof: false,
        requireLocationProof: false,
        ...input,
      },
    })
  }

  viewBroadcast(token: string, broadcastId: number): Promise<BroadcastReceiver> {
    return this.request(`/broadcast/${broadcastId}/view`, { method: 'POST', token })
  }

  confirmBroadcast(
    token: string,
    broadcastId: number,
    status: string,
  ): Promise<BroadcastReceiver> {
    return this.request(`/broadcast/${broadcastId}/confirm`, {
      method: 'POST',
      token,
      body: { status },
    })
  }

  broadcastStats(token: string, broadcastId: number): Promise<BroadcastStats> {
    return this.request(`/broadcast/${broadcastId}/stats`, { token })
  }

  private captureResponseCookies(headers: Headers): void {
    const extended = headers as Headers & { getSetCookie?: () => string[] }
    const values = typeof extended.getSetCookie === 'function'
      ? extended.getSetCookie()
      : [headers.get('set-cookie')].filter((value): value is string => Boolean(value))

    for (const value of values) {
      const parts = value.split(';').map((item) => item.trim())
      const separator = parts[0]?.indexOf('=') ?? -1
      if (separator <= 0) continue
      const name = parts[0].slice(0, separator)
      const cookieValue = parts[0].slice(separator + 1)
      const attributes = new Map<string, string>()
      for (const attribute of parts.slice(1)) {
        const index = attribute.indexOf('=')
        attributes.set(
          (index < 0 ? attribute : attribute.slice(0, index)).toLowerCase(),
          index < 0 ? '' : attribute.slice(index + 1),
        )
      }
      const maxAge = Number(attributes.get('max-age'))
      if (!cookieValue || (Number.isFinite(maxAge) && maxAge <= 0)) {
        this.cookies.delete(name)
        continue
      }
      this.cookies.set(name, {
        value: cookieValue,
        path: attributes.get('path') || '/',
      })
    }
  }
}

export interface FriendPair {
  alice: AuthSession
  bob: AuthSession
  aliceApi: ApiClient
  bobApi: ApiClient
  password: string
  aliceUsername: string
  bobUsername: string
  conversationId: string
}

export interface FriendPairOptions {
  aliceUrl?: string
  bobUrl?: string
}

export function uniqueId(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`
}

export async function createUser(
  api: ApiClient,
  prefix = 'e2e_user',
): Promise<{ session: AuthSession; username: string; password: string }> {
  const suffix = uniqueId('u').replace(/_/g, '').slice(-16)
  const username = `${prefix}_${suffix}`
  const password = 'E2ePassword2026'
  await api.register(username, password, `User ${suffix}`.slice(0, 16))
  return { session: await api.login(username, password), username, password }
}

export async function createFriendPair(
  options: FriendPairOptions = {},
): Promise<FriendPair> {
  const aliceApi = new ApiClient(
    options.aliceUrl
      || process.env.E2E_INSTANCE_A_URL
      || 'http://127.0.0.1:18081',
  )
  const bobApi = new ApiClient(
    options.bobUrl
      || process.env.E2E_INSTANCE_B_URL
      || 'http://127.0.0.1:18082',
  )
  const suffix = uniqueId('pair').replace(/_/g, '').slice(-16)
  const aliceUsername = `e2e_a_${suffix}`
  const bobUsername = `e2e_b_${suffix}`
  const password = 'E2ePassword2026'

  await aliceApi.register(aliceUsername, password, `Alice ${suffix}`.slice(0, 16))
  await bobApi.register(bobUsername, password, `Bob ${suffix}`.slice(0, 16))
  const alice = await aliceApi.login(aliceUsername, password)
  const bob = await bobApi.login(bobUsername, password)

  await aliceApi.sendFriendRequest(alice.token, bob.userId)
  const request = (await bobApi.friendRequests(bob.token))
    .find((candidate) => candidate.fromUserId === alice.userId)
  if (!request) throw new Error('Friend request was not visible to its recipient')
  await bobApi.acceptFriendRequest(bob.token, request.id)

  const [low, high] = [alice.userId, bob.userId].sort((a, b) => a - b)
  return {
    alice,
    bob,
    aliceApi,
    bobApi,
    password,
    aliceUsername,
    bobUsername,
    conversationId: `private:${low}:${high}`,
  }
}
