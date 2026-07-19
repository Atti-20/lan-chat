export interface User {
  id: number
  userId?: number
  username: string
  nickname: string
  avatar?: string
  signature?: string
  online?: number
  status?: number
  canSendBroadcast?: number
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

export type TemporaryRoomStatus = 'ACTIVE' | 'EXPIRING' | 'FROZEN' | 'ARCHIVED' | 'DESTROYED'
export type TemporaryRoomExpiryAction = 'FREEZE' | 'ARCHIVE' | 'DESTROY'

export interface TemporaryRoom {
  id: number
  conversationId: string
  roomName: string
  purpose?: string
  ownerId: number
  roomCode?: string
  status: TemporaryRoomStatus
  expiresAt: string
  maxMembers: number
  memberCount?: number
  currentUserRole?: 'OWNER' | 'ADMIN' | 'MEMBER' | 'READ_ONLY'
  allowGuests: boolean
  allowMemberInvite: boolean
  allowFileUpload: boolean
  allowFileDownload: boolean
  allowForward: boolean
  messageRetentionDays: number
  allowExternalSync: boolean
  expireAction: TemporaryRoomExpiryAction
  createTime?: string
  updateTime?: string
}

export interface TemporaryRoomCreatePayload {
  roomName: string
  purpose?: string
  expiresAt: string
  maxMembers: number
  allowGuests: boolean
  allowMemberInvite: boolean
  allowFileUpload: boolean
  allowFileDownload: boolean
  allowForward: boolean
  messageRetentionDays: number
  allowExternalSync: boolean
  expireAction: TemporaryRoomExpiryAction
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
  kind: 'private' | 'group' | 'temporary'
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
  source: Friend | ChatGroup | TemporaryRoom
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
  id: number
  url: string
  thumbnailUrl?: string
  originalName: string
  fileName: string
  fileSize: number
  fileType?: string
  fileHash?: string
  instantUpload?: boolean
}

export type ResumableUploadStatus =
  | 'UPLOADING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'

export interface ResumableUploadSession {
  uploadId: string
  status: ResumableUploadStatus
  chunkSize: number
  totalParts: number
  uploadedParts: number[]
  expiresAt?: string
  completedFile?: FileUpload | null
}

export type FileTransferPath = 'PEER_TO_PEER' | 'NODE_RELAY'

export interface FileAttachmentData {
  url?: string
  originalUrl?: string
  thumbnailUrl?: string
  name?: string
  size?: number
  mime?: string
  fileHash?: string
  transferId?: string
  transferPath?: FileTransferPath
}

export interface DirectFileRecord {
  transferId: string
  name: string
  mime: string
  size: number
  fileHash: string
  blob: Blob
  savedAt: string
}

export type BroadcastPriority = 'NORMAL' | 'IMPORTANT' | 'EMERGENCY'
export type BroadcastScopeType = 'ALL' | 'GROUP' | 'USERS'
export type BroadcastReceiptStatus =
  | 'PENDING'
  | 'DELIVERED'
  | 'VIEWED'
  | 'RECEIVED'
  | 'EXECUTED'
  | 'NEED_SUPPORT'
  | 'EXPIRED'

export type BroadcastStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELLED'

export interface BroadcastLocation {
  latitude: number
  longitude: number
  accuracyMeters?: number
  addressText?: string
  capturedAt?: string
}

export interface BroadcastSender {
  id: number
  username: string
  nickname?: string
  avatar?: string
}

export interface BroadcastContentEvidence {
  imageUrls: readonly string[]
  location?: BroadcastLocation
}

export interface EmergencyBroadcast {
  id: number
  title: string
  content: string
  priority: BroadcastPriority
  scopeType: BroadcastScopeType
  scopeGroupId?: number
  senderId: number
  confirmationRequired: boolean
  confirmationOptions?: string
  deadlineAt?: string
  bypassMute: boolean
  repeatReminder: boolean
  status: BroadcastStatus
  requireImageProof: boolean
  requireLocationProof: boolean
  completedAt?: string
  createTime: string
  updateTime?: string
}

export interface BroadcastReceiver {
  id: number
  broadcastId: number
  userId: number
  deliveredAt?: string
  viewedAt?: string
  confirmStatus: BroadcastReceiptStatus | 'NOT_REQUIRED'
  confirmedAt?: string
  confirmDeviceType?: string
  createTime: string
  updateTime?: string
  targetStatus?: 'ACTIVE' | 'REMOVED'
  completedAt?: string
}

export interface BroadcastDetail {
  broadcast: EmergencyBroadcast
  receiver?: BroadcastReceiver
  sender: BroadcastSender
  contentEvidence?: BroadcastContentEvidence
  confirmationOptions: readonly string[]
  createdByCurrentUser: boolean
}

export interface BroadcastCreatePayload {
  title: string
  content: string
  priority: BroadcastPriority
  scopeType: BroadcastScopeType
  groupId?: number
  receiverIds?: number[]
  confirmationRequired: boolean
  confirmationOptions?: string[]
  deadlineAt?: string
  bypassMute: boolean
  repeatReminder: boolean
  requireImageProof: boolean
  requireLocationProof: boolean
  contentImageFileIds?: number[]
  contentLocation?: BroadcastLocation
}

export interface BroadcastCompletePayload {
  imageFileIds: number[]
  location?: BroadcastLocation
}

export interface BroadcastRecipientDetail {
  receiverId: number
  userId: number
  username: string
  nickname: string
  avatar?: string
  targetStatus: 'ACTIVE' | 'REMOVED'
  confirmStatus: BroadcastReceiptStatus | 'NOT_REQUIRED'
  deliveredAt?: string
  viewedAt?: string
  completedAt?: string
  imageUrls: readonly string[]
  location?: BroadcastLocation
  remindCount: number
  lastRemindedAt?: string
}

export interface BroadcastTargetUpdatePayload {
  addUserIds: number[]
  removeUserIds: number[]
}

export interface BroadcastTargetCandidate {
  userId: number
  username: string
  nickname: string
  avatar?: string
}

export interface BroadcastTargetUpdateResult {
  addedUserIds: number[]
  removedUserIds: number[]
}

export interface BroadcastStatistics {
  broadcastId: number
  targetCount: number
  deliveredCount: number
  viewedCount: number
  confirmedCount: number
  unconfirmedCount: number
  executedCount: number
  needSupportCount: number
  removedCount: number
  unconfirmedUserIds: readonly number[]
  expiredCount: number
  expired: boolean
  confirmationCounts: Readonly<Record<string, number>>
}

export interface DeviceLogin {
  id: number
  userId: number
  deviceType: string
  deviceName: string
  loginTime: string
  expireTime?: string
  status: number
  current?: boolean
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

export type ConnectionPath = 'LOCAL' | 'LAN' | 'REMOTE'

export interface NodePublicInfo {
  nodeId: string
  nodeName: string
  organizationName: string
  version: string
  mode: 'LOCAL_INDEPENDENT' | 'LAN_FIRST' | 'HYBRID'
  serviceStatus: string
  secure: boolean
  discoveryEnabled: boolean
  selfRegistrationEnabled: boolean
  loginMethods: readonly string[]
  capabilities: readonly string[]
  serverTime: number
  protocolVersion: number
  apiBasePath: string
  webSocketPath: string
  healthPath: string
  appPath: string
  desktopAuthSupported: boolean
  refreshTransport: string
}

export interface DependencyStatus {
  status: 'UP' | 'DOWN'
  latencyMs: number | null
  message?: string
}

export interface StorageStatus {
  status: 'UP' | 'DOWN'
  path: string
  totalBytes: number
  usableBytes: number
  usedBytes: number
  usedPercent: number
  message?: string
}

export interface JvmStatus {
  heapUsedBytes: number
  heapMaxBytes: number
  threadCount: number
  availableProcessors: number
  systemLoadAverage: number
}

export interface ConnectionLifecycleEvent {
  timestamp: string
  event: string
  userId?: number
  remoteAddress?: string
  reason?: string
}

export interface AdminDiagnostics {
  nodeId: string
  nodeName: string
  mode: string
  version: string
  startedAt: string
  uptimeSeconds: number
  onlineUsers: number
  webSocketConnections: number
  webSocketEvents: number
  chatAcknowledgements: number
  webSocketFailures: number
  averageEventProcessingMs: number
  database: DependencyStatus
  redis: DependencyStatus
  storage: StorageStatus
  jvm: JvmStatus
  recentConnections: readonly ConnectionLifecycleEvent[]
  warnings: readonly string[]
}

export interface DiscoveredNode {
  nodeId: string
  nodeName: string
  organizationName: string
  version: string
  mode: string
  appUrl: string
  secure: boolean
  current: boolean
  lastSeenAt: string
}

export type RuntimeLogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
export type RuntimeLogLevelFilter = 'ALL' | RuntimeLogLevel

export interface RuntimeLogEntry {
  sequence: number
  timestamp: string
  level: RuntimeLogLevel
  thread: string
  requestId: string
  logger: string
  message: string
  details?: string | null
  explanation?: string | null
}

export interface RuntimeLogSnapshot {
  available: boolean
  fileName: string
  fileSizeBytes: number
  updatedAt?: string | null
  scannedEntries: number
  truncated: boolean
  levelCounts: Record<RuntimeLogLevel, number>
  entries: readonly RuntimeLogEntry[]
  notice: string
}
