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

type RequestOptions = {
  method?: string
  token?: string
  body?: unknown
}

export class ApiClient {
  constructor(readonly baseUrl: string) {}

  async request<T>(
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    const headers = new Headers()
    if (options.body !== undefined) {
      headers.set('content-type', 'application/json')
    }
    if (options.token) {
      headers.set('authorization', `Bearer ${options.token}`)
    }

    const response = await fetch(`${this.baseUrl}/api/v1${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body === undefined
        ? undefined
        : JSON.stringify(options.body),
    })
    const result = await response.json() as ApiResult<T>
    if (!response.ok || result.code !== 200) {
      throw new Error(
        `${options.method || 'GET'} ${path} failed: `
        + `${response.status}/${result.code} ${result.msg}`,
      )
    }
    return result.data
  }

  register(
    username: string,
    password: string,
    nickname: string,
  ): Promise<void> {
    return this.request('/auth/register', {
      method: 'POST',
      body: { username, password, nickname },
    })
  }

  login(username: string, password: string): Promise<AuthSession> {
    return this.request('/auth/login', {
      method: 'POST',
      body: {
        username,
        password,
        deviceType: 'web',
        deviceName: 'LANChat E2E',
      },
    })
  }

  sendFriendRequest(token: string, toUserId: number): Promise<void> {
    return this.request('/friend/request', {
      method: 'POST',
      token,
      body: {
        toUserId,
        message: 'E2E friendship setup',
      },
    })
  }

  friendRequests(token: string): Promise<FriendRequest[]> {
    return this.request('/friend/requests', { token })
  }

  acceptFriendRequest(token: string, requestId: number): Promise<void> {
    return this.request('/friend/handle', {
      method: 'POST',
      token,
      body: {
        requestId,
        accept: true,
      },
    })
  }

  history(
    token: string,
    conversationId: string,
  ): Promise<ChatMessage[]> {
    const query = new URLSearchParams({
      conversationId,
      limit: '100',
    })
    return this.request(`/chat/history?${query}`, { token })
  }
}

export interface FriendPair {
  alice: AuthSession
  bob: AuthSession
  aliceApi: ApiClient
  bobApi: ApiClient
  conversationId: string
}

export async function createFriendPair(): Promise<FriendPair> {
  const aliceApi = new ApiClient(
    process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081',
  )
  const bobApi = new ApiClient(
    process.env.E2E_INSTANCE_B_URL || 'http://127.0.0.1:18082',
  )
  const suffix = `${Date.now().toString(36)}${Math.random()
    .toString(36)
    .slice(2, 8)}`
  const aliceUsername = `e2e_a_${suffix}`
  const bobUsername = `e2e_b_${suffix}`
  const password = 'E2ePassword2026'

  await aliceApi.register(aliceUsername, password, `Alice ${suffix}`)
  await bobApi.register(bobUsername, password, `Bob ${suffix}`)
  const alice = await aliceApi.login(aliceUsername, password)
  const bob = await bobApi.login(bobUsername, password)

  await aliceApi.sendFriendRequest(alice.token, bob.userId)
  const request = (await bobApi.friendRequests(bob.token))
    .find((candidate) => candidate.fromUserId === alice.userId)
  if (!request) {
    throw new Error('Friend request was not visible on the second instance')
  }
  await bobApi.acceptFriendRequest(bob.token, request.id)

  const [low, high] = [alice.userId, bob.userId].sort((a, b) => a - b)
  return {
    alice,
    bob,
    aliceApi,
    bobApi,
    conversationId: `private:${low}:${high}`,
  }
}
