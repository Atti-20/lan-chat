export interface User {
  id: number
  userId?: number
  username: string
  nickname: string
  avatar?: string
  signature?: string
  online?: number
  status?: number
  lastLoginAt?: string
}

export interface AdminUser extends User {
  muteStart?: string
  muteEnd?: string
}

export interface AuthSession {
  userId: number
  username: string
  nickname: string
  avatar?: string
  token: string
  expiresIn: number
}

export interface Friend extends User {
  friendId: number
  remark?: string
  groupName?: string
  isPinned?: number
  isMuted?: number
  lastMessage?: string
  lastMessageType?: string
  lastMessageTime?: string
}

export interface ChatGroup {
  id: number
  groupName: string
  avatar?: string
  announcement?: string
  ownerId: number
  maxMembers?: number
  joinMode?: number
  lastMessage?: string
  lastMessageType?: string
  lastMessageTime?: string
}

export interface FriendRequest {
  id: number
  fromUserId: number
  toUserId: number
  message?: string
  createTime?: string
  sender?: User
}

export interface GroupMember {
  userId: number
  nickname: string
  avatar?: string
  role: number
  online?: number
  muteUntil?: string
}

export interface Conversation {
  id: number
  conversationId: string
  kind: 'private' | 'group'
  name: string
  avatar?: string
  subtitle?: string
  lastMessage?: string
  lastMessageType?: string
  lastMessageTime?: string
  online?: boolean
  pinned?: boolean
  muted?: boolean
  unreadCount?: number
  pendingCount?: number
  source: Friend | ChatGroup
}

export type MessageDeliveryState =
  | 'WAITING_NETWORK'
  | 'SENDING'
  | 'SENT'
  | 'DELIVERED'
  | 'READ'
  | 'FAILED'

export interface ChatMessage {
  messageId: string
  clientMsgId?: string
  conversationId?: string
  sequence?: number
  fromUserId: number
  senderDeviceId?: number
  fromNickname?: string
  fromAvatar?: string
  toUserId?: number
  groupId?: number
  type?: string
  contentType?: string
  content?: string
  replyToId?: string
  mentionUserIds?: string
  isBurn?: number | boolean
  burnDuration?: number
  isRecalled?: number
  status?: number
  deliveryState?: MessageDeliveryState
  errorMessage?: string
  clientCreatedAt?: string
  createTime?: string
  timestamp?: string
}

export interface FileUpload {
  url: string
  thumbnailUrl?: string
  originalName: string
  fileName: string
  fileSize: number
  fileType?: string
  fileHash?: string
  instantUpload?: boolean
}

export interface DeviceLogin {
  id: number
  userId: number
  deviceType: string
  deviceName: string
  loginTime: string
  expireTime?: string
  status: number
}

export type ConnectionState =
  | 'CONNECTING'
  | 'AUTHENTICATING'
  | 'SYNCING'
  | 'ONLINE'
  | 'DEGRADED'
  | 'RECONNECTING'
  | 'OFFLINE'

export interface WsEnvelope<TPayload extends Record<string, unknown> = Record<string, unknown>> {
  version: 1
  event: string
  requestId?: string
  clientMsgId?: string
  conversationId?: string
  timestamp: number
  payload: TPayload
}

export interface ChatSendPayload extends Record<string, unknown> {
  toUserId?: number
  groupId?: number
  contentType: string
  content: string
  replyToId?: string | null
  mentionUserIds?: string | null
  isBurn: boolean
}

export interface OutboxEntry {
  clientMsgId: string
  requestId: string
  conversationId: string
  payload: ChatSendPayload
  createdAt: string
  retryCount: number
  state: 'WAITING_NETWORK' | 'SENDING' | 'FAILED'
  lastError?: string
}
