<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import type { ChatSection } from '../../composables/useChat'
import type { ChatMessage, Conversation, FriendRequest } from '../../types'
import { conversationPreview, formatMessageTime, formatTime } from '../../utils/format'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon, { type IconName } from '../base/UiIcon.vue'

interface Props {
  section: ChatSection
  conversations: readonly Conversation[]
  allConversations: readonly Conversation[]
  messageResults: readonly ChatMessage[]
  messageSearchLoading?: boolean
  messageSearchError?: string
  requests: readonly FriendRequest[]
  selectedId?: number
  selectedKind?: Conversation['kind']
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  selectedId: undefined,
  selectedKind: undefined,
  loading: false,
  messageSearchLoading: false,
  messageSearchError: '',
})
const query = defineModel<string>('query', { required: true })
const emit = defineEmits<{
  select: [conversation: Conversation]
  handleRequest: [requestId: number, accept: boolean]
  searchPeople: []
  createGroup: []
  createTemporaryRoom: []
  joinTemporaryRoom: []
}>()
const moreOpen = shallowRef(false)
const headerActionsRef = useTemplateRef<HTMLElement>('headerActions')

function closeMoreMenu(): void {
  moreOpen.value = false
}

function chooseMoreAction(action: 'searchPeople' | 'createGroup' | 'createTemporaryRoom' | 'joinTemporaryRoom'): void {
  closeMoreMenu()
  if (action === 'searchPeople') emit('searchPeople')
  else if (action === 'createGroup') emit('createGroup')
  else if (action === 'createTemporaryRoom') emit('createTemporaryRoom')
  else emit('joinTemporaryRoom')
}

function handleDocumentPointerDown(event: PointerEvent): void {
  if (!moreOpen.value || headerActionsRef.value?.contains(event.target as Node)) return
  closeMoreMenu()
}

watch(() => props.section, closeMoreMenu)
onMounted(() => document.addEventListener('pointerdown', handleDocumentPointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handleDocumentPointerDown))

const kicker = computed(() => ({
  messages: 'MESSAGE',
  contacts: 'FRIENDS',
  groups: 'GROUPS',
  broadcasts: 'BROADCASTS',
  admin: 'ADMINISTRATION',
}[props.section]))
const title = computed(() => ({
  messages: '消息',
  contacts: '好友',
  groups: '群组',
  broadcasts: '广播',
  admin: '管理',
}[props.section]))
const searchPlaceholder = computed(() => ({
  messages: '搜索对话',
  contacts: '搜索好友',
  groups: '搜索群组',
  broadcasts: '搜索广播',
  admin: '搜索管理模块',
}[props.section]))
const emptyIcons: Record<ChatSection, IconName> = {
  messages: 'messages',
  contacts: 'contacts',
  groups: 'groups',
  broadcasts: 'bell',
  admin: 'admin',
}
const emptyIcon = computed(() => emptyIcons[props.section])
const emptyCopy = computed(() => {
  if (query.value.trim()) return '没有匹配的结果'
  if (props.section === 'admin') return '管理控制台已打开'
  if (props.section === 'groups') return '还没有加入群聊'
  if (props.section === 'contacts') return '搜索并添加第一位好友'
  return '新的对话会出现在这里'
})
const emptyDetail = computed(() => {
  if (query.value.trim()) return '调整关键词后再试一次。'
  if (props.section === 'groups') return '使用右上角按钮创建新的群组。'
  if (props.section === 'contacts') return '使用右上角按钮搜索并添加好友。'
  return '使用右上角按钮开始新的连接。'
})

interface ConversationListItem {
  key: string
  conversation: Conversation
  preview: string
  time?: string
  messageHit: boolean
}

const conversationMap = computed(() => new Map(
  props.allConversations.map((conversation) => [conversation.conversationId, conversation]),
))
const messageSearchMode = computed(() => props.section === 'messages' && query.value.trim().length > 0)
const matchingConversationItems = computed<ConversationListItem[]>(() => {
  if (!messageSearchMode.value) return []
  const needle = query.value.trim().toLocaleLowerCase('zh-CN')
  return props.allConversations
    .filter((conversation) => conversation.name.toLocaleLowerCase('zh-CN').includes(needle))
    .map((conversation) => ({
      key: `conversation-${conversation.kind}-${conversation.id}`,
      conversation,
      preview: conversation.lastMessage || '还没有消息',
      time: conversation.lastMessageTime,
      messageHit: false,
    }))
})
const messageSearchItems = computed<ConversationListItem[]>(() => {
  if (!messageSearchMode.value) return []
  const seen = new Set<string>()
  return props.messageResults.flatMap((message) => {
    const conversation = conversationMap.value.get(message.conversationId || '')
    if (!conversation) return []
    const key = `${conversation.kind}-${conversation.id}`
    if (seen.has(key)) return []
    seen.add(key)
    return [{
      key: `message-${message.messageId}`,
      conversation,
      preview: conversationPreview(message.type || message.contentType, message.content),
      time: message.createTime || message.timestamp,
      messageHit: true,
    }]
  })
})
const searchConversationKeys = computed(() => new Set(
  matchingConversationItems.value.map((item) => `${item.conversation.kind}-${item.conversation.id}`),
))
const listItems = computed<ConversationListItem[]>(() => {
  if (messageSearchMode.value) {
    return [
      ...matchingConversationItems.value,
      ...messageSearchItems.value.filter((item) => !searchConversationKeys.value.has(
        `${item.conversation.kind}-${item.conversation.id}`,
      )),
    ]
  }
  return props.conversations.map((conversation) => ({
    key: `conversation-${conversation.kind}-${conversation.id}`,
    conversation,
    preview: conversation.lastMessage || '还没有消息',
    time: conversation.lastMessageTime,
    messageHit: false,
  }))
})
const messageSearchWaiting = computed(() => messageSearchMode.value
  && query.value.trim().length >= 2
  && props.messageSearchLoading)
const messageSearchTooShort = computed(() => messageSearchMode.value && query.value.trim().length < 2)
const messageSearchEmpty = computed(() => messageSearchMode.value
  && !messageSearchWaiting.value
  && !props.messageSearchError
  && !messageSearchTooShort.value
  && listItems.value.length === 0)

function itemTime(item: ConversationListItem): string {
  return item.messageHit ? formatMessageTime(item.time) : formatTime(item.time)
}
</script>

<template>
  <aside class="conversation-sidebar apple-structural-surface">
    <header class="sidebar-header">
      <div>
        <p>{{ kicker }}</p>
        <h1>{{ title }}</h1>
      </div>
      <div ref="headerActions" class="header-actions">
        <button
          class="mini-button"
          type="button"
          aria-label="更多操作"
          :aria-expanded="moreOpen"
          aria-haspopup="menu"
          @click="moreOpen = !moreOpen"
        >
          <UiIcon name="plus" :size="18" />
        </button>
        <div v-if="moreOpen" class="action-menu" role="menu" aria-label="更多操作">
          <button type="button" role="menuitem" @click="chooseMoreAction('searchPeople')">添加好友</button>
          <button type="button" role="menuitem" @click="chooseMoreAction('createGroup')">创建群聊</button>
          <template v-if="section === 'groups'">
            <button type="button" role="menuitem" @click="chooseMoreAction('createTemporaryRoom')">创建临时房间</button>
            <button type="button" role="menuitem" @click="chooseMoreAction('joinTemporaryRoom')">凭房间码加入</button>
          </template>
        </div>
      </div>
    </header>

    <label class="sidebar-search">
      <span class="sr-only">搜索当前列表</span>
      <UiIcon name="search" :size="17" />
      <input v-model="query" type="search" :placeholder="searchPlaceholder" />
    </label>

    <div v-if="loading" class="sidebar-loading" aria-label="正在载入">
      <span v-for="index in 5" :key="index" />
    </div>

    <div v-else class="conversation-list">
      <div v-if="!messageSearchMode && section === 'contacts' && requests.length > 0" class="request-section">
        <div class="request-section-title">好友申请</div>
        <article v-for="request in requests" :key="request.id" class="request-card">
          <UserAvatar :name="request.sender?.nickname || `用户${request.fromUserId}`" :avatar="request.sender?.avatar" :size="46" />
          <div class="request-copy">
            <strong>{{ request.sender?.nickname || `用户 ${request.fromUserId}` }}</strong>
            <p>{{ request.message || '想添加你为好友' }}</p>
            <span>{{ formatTime(request.createTime) }}</span>
          </div>
          <div class="request-actions">
            <button type="button" class="accept" @click="emit('handleRequest', request.id, true)">接受</button>
            <button type="button" @click="emit('handleRequest', request.id, false)">忽略</button>
          </div>
        </article>
      </div>

      <button
        v-for="item in listItems"
        :key="item.key"
        type="button"
        class="conversation-item apple-list-row"
        :class="{ 'conversation-item--active': selectedId === item.conversation.id && selectedKind === item.conversation.kind }"
        @click="emit('select', item.conversation)"
      >
        <UserAvatar :name="item.conversation.name" :avatar="item.conversation.avatar" :size="50" :online="item.conversation.online" />
        <span class="conversation-copy">
          <span class="conversation-line">
            <strong>{{ item.conversation.name }}</strong>
            <time>{{ itemTime(item) }}</time>
          </span>
          <span class="conversation-line conversation-preview">
            <span>{{ item.preview }}</span>
            <i v-if="!item.messageHit && item.conversation.unreadCount" class="unread-badge" :aria-label="`${item.conversation.unreadCount} 条未读消息`">
              {{item.conversation.unreadCount > 99 ? '99+' : item.conversation.unreadCount}}
            </i>
            <i v-if="!item.messageHit && item.conversation.pendingCount" class="pending" :aria-label="`${item.conversation.pendingCount} 条待发送`">
              {{ item.conversation.pendingCount }}
            </i>
            <i v-if="!item.messageHit && item.conversation.muted" aria-label="已免打扰">⌁</i>
          </span>
        </span>
        <span v-if="!item.messageHit && item.conversation.pinned" class="conversation-pin">
          <UiIcon name="pin" :size="15" label="已置顶" />
        </span>
      </button>

      <p v-if="messageSearchWaiting" class="search-state">正在搜索消息…</p>
      <p v-else-if="messageSearchTooShort" class="search-state">请输入至少 2 个字符。</p>
      <p v-else-if="messageSearchError" class="search-state search-state--error">{{ messageSearchError }}</p>
      <div v-else-if="messageSearchEmpty || (!messageSearchMode && conversations.length === 0)" class="empty-list">
        <span class="empty-icon">
          <UiIcon :name="emptyIcon" :size="24" />
        </span>
        <strong>{{ emptyCopy }}</strong>
        <p>{{ emptyDetail }}</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.conversation-sidebar { display: flex; width: 330px; min-width: 0; min-height: 0; flex-direction: column; border-radius: 18px; overflow: hidden; }
.sidebar-header { display: flex; padding: 24px 22px 16px; align-items: center; justify-content: space-between; }
.sidebar-header p { margin: 0 0 2px; color: #5480aa; font-family: "SF Mono", monospace; font-size: 9px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
.sidebar-header h1 { margin: 0; font-size: 25px; letter-spacing: -.04em; }
.header-actions { position: relative; display: flex; gap: 7px; }
.action-menu {
  position: absolute;
  z-index: 12;
  top: calc(100% + 8px);
  right: 0;
  display: grid;
  min-width: 132px;
  padding: 5px;
  gap: 2px;
  border: 1px solid var(--glass-border);
  border-radius: 13px;
  background: var(--surface-raise);
  box-shadow: 0 12px 28px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
}
.action-menu button {
  min-height: 38px;
  padding: 0 11px;
  border: 0;
  border-radius: 9px;
  color: var(--ink);
  font: inherit;
  font-size: 12px;
  text-align: left;
  background: transparent;
  cursor: pointer;
}
.action-menu button:hover,
.action-menu button:focus-visible { color: var(--blue); background: var(--hover); }
.action-menu button:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 45%, transparent); outline-offset: -2px; }
.mini-button { display: grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 1px solid rgba(255,255,255,.75); border-radius: 13px; color: #3b5c7c; background: rgba(255,255,255,.46); box-shadow: inset 0 1px 0 #fff; cursor: pointer; }
.mini-button:hover { color: var(--blue); background: rgba(255,255,255,.72); }
.mini-button .ui-icon { width: 18px; }
.sidebar-search { display: flex; min-height: 42px; margin: 0 16px 12px; padding: 0 12px; align-items: center; gap: 8px; border: 1px solid rgba(144,169,194,.17); border-radius: 14px; background: rgba(221,235,248,.42); }
.sidebar-search .ui-icon { width: 17px; color: var(--ink-faint); }
.sidebar-search input { width: 100%; min-width: 0; border: 0; outline: none; color: var(--ink); background: none; }
.sidebar-search input::-webkit-search-cancel-button { display: none; }
.sidebar-search kbd { padding: 3px 5px; border: 1px solid rgba(138,163,188,.2); border-radius: 5px; color: #8293a5; font-family: inherit; font-size: 9px; background: rgba(255,255,255,.4); }
.conversation-list { display: flex; min-height: 0; flex: 1; flex-direction: column; padding: 0 10px 14px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: rgba(92,124,156,.22) transparent; }
.conversation-item { display: flex; width: 100%; min-height: 74px; padding: 10px; align-items: center; gap: 12px; border: 1px solid transparent; border-radius: 19px 15px 19px 15px; text-align: left; background: transparent; cursor: pointer; transition: 200ms var(--ease-liquid); }
.conversation-item:hover { background: rgba(255,255,255,.42); transform: translateX(2px); }
.conversation-item--active { border-color: rgba(255,255,255,.82); background: linear-gradient(145deg, rgba(255,255,255,.78), rgba(218,239,255,.52)); box-shadow: inset 0 1px 0 #fff, 0 9px 20px rgba(51,93,136,.1); }
.conversation-copy { display: grid; min-width: 0; flex: 1; gap: 6px; }
.conversation-line { display: flex; min-width: 0; align-items: center; gap: 8px; }
.conversation-line strong { overflow: hidden; flex: 1; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.conversation-line time { color: var(--ink-faint); font-size: 10px; }
.conversation-preview { color: #718398; font-size: 12px; }
.conversation-preview > span { overflow: hidden; flex: 1; text-overflow: ellipsis; white-space: nowrap; }
.conversation-preview i { font-style: normal; }
.conversation-preview .unread-badge {display: grid; min-width: 18px; height: 18px; padding: 0 5px; place-items: center; flex: 0 0 auto; border-radius: 999px; color: white; font-size: 9px; font-weight: 700; font-style: normal; background: var(--coral)}
.conversation-preview .pending { min-width: 17px; padding: 1px 5px; border-radius: 999px; color: var(--blue); font-size: 9px; font-style: normal; text-align: center; background: rgba(0,122,255,.1); }
.conversation-pin { display: grid; width: 20px; height: 20px; margin-left: auto; place-items: center; color: var(--blue); }
.conversation-pin .ui-icon { width: 15px; height: 15px; }
.request-section { margin-bottom: 4px; }
.request-section-title { padding: 10px 10px 6px; color: var(--ink-faint); font-size: 11px; font-weight: 700; }
.request-card { display: grid; padding: 14px; grid-template-columns: auto 1fr; gap: 11px; border: 1px solid rgba(255,255,255,.68); border-radius: 20px 16px 20px 16px; background: rgba(255,255,255,.4); box-shadow: inset 0 1px 0 rgba(255,255,255,.85); }
.request-copy { min-width: 0; }
.request-copy strong { font-size: 13px; }
.request-copy p { margin: 3px 0; overflow: hidden; color: var(--ink-soft); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.request-copy span { color: var(--ink-faint); font-size: 9px; }
.request-actions { display: flex; grid-column: 1 / -1; gap: 8px; }
.request-actions button { min-height: 32px; padding: 0 13px; border: 0; border-radius: 11px; color: var(--on-surface); font-size: 11px; font-weight: 700; background: var(--fill); cursor: pointer; }
.request-actions .accept { color: white; background: var(--blue); box-shadow: 0 6px 13px rgba(10,132,255,.2); }
.empty-list { display: grid; min-height: 260px; flex: 1; padding: 30px; place-items: center; align-content: center; text-align: center; }
.empty-list > span { display: grid; width: 52px; height: 52px; margin-bottom: 15px; place-items: center; border: 1px solid rgba(255,255,255,.8); border-radius: 19px; color: var(--blue); font-size: 23px; background: rgba(255,255,255,.48); box-shadow: inset 0 1px 0 #fff; }
.empty-list strong { font-size: 14px; }
.empty-list p { margin: 6px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.5; }
.search-state { margin: 0; padding: 28px 16px; color: var(--ink-faint); font-size: 12px; line-height: 1.5; text-align: center; }
.search-state--error { color: var(--coral); }
.sidebar-loading { display: grid; padding: 10px 18px; gap: 14px; }
.sidebar-loading span { height: 58px; border-radius: 17px; background: linear-gradient(90deg, rgba(255,255,255,.18), rgba(255,255,255,.62), rgba(255,255,255,.18)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
@keyframes shimmer { to { background-position: -200% 0; } }

@media (max-width: 760px) {
  .conversation-sidebar { width: 100%; border-radius: 25px; padding-bottom: 76px; }
}

.conversation-sidebar {
  width: 100%;
  border-right: 1px solid var(--separator);
  border-radius: 0;
  background: var(--surface-raise);
}
.sidebar-header { padding: 22px 18px 13px; }
.sidebar-header p {
  margin-bottom: 3px;
  color: var(--blue);
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0;
  text-transform: none;
}
.sidebar-header h1 { font-size: 24px; font-weight: 700; letter-spacing: -0.03em; }
.header-actions { gap: 5px; }
.mini-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  color: var(--on-surface);
  background: var(--fill);
  box-shadow: none;
}
.mini-button:hover { color: var(--blue); background: var(--button-hover); }
.sidebar-search {
  min-height: 38px;
  margin: 0 12px 10px;
  border: 0;
  border-radius: 11px;
  background: var(--fill);
}
.sidebar-search kbd { border-color: var(--separator); background: rgba(255, 255, 255, 0.58); }
.conversation-list { padding: 0 8px 12px; }
.conversation-item {
  min-height: 68px;
  padding: 9px 10px;
  border: 0;
  border-radius: 12px;
  gap: 11px;
}
.conversation-item:hover { background: var(--hover); transform: none; }
.conversation-item--active { background: var(--active); box-shadow: none; }
.conversation-copy { gap: 5px; }
.conversation-line strong { font-size: 14px; }
.conversation-line time { font-size: 10px; }
.conversation-preview { color: var(--ink-faint); font-size: 12px; }
.request-section { margin-bottom: 6px; padding-bottom: 4px; border-bottom: 1px solid var(--separator); }
.request-section-title { padding: 8px 10px 4px; }
.request-card {
  padding: 12px 10px;
  border: 0;
  border-bottom: 1px solid var(--separator);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.empty-list > span,
.empty-list > .empty-icon {
  border: 0;
  border-radius: 50%;
  background: var(--fill);
  box-shadow: none;
}
.empty-icon { display: grid; place-items: center; width: 52px; height: 52px; }
.empty-icon .ui-icon { width: 24px; color: var(--blue); }
.sidebar-loading span { height: 54px; border-radius: 11px; background-color: var(--fill); }

@media (max-width: 760px) {
  .conversation-sidebar {
    width: 100%;
    padding-bottom: calc(88px + env(safe-area-inset-bottom));
    border: 0;
    border-radius: 0;
    background: var(--surface);
  }
  .sidebar-header {
    padding: max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) 12px max(16px, env(safe-area-inset-left));
  }
  .mini-button { width: 40px; height: 40px; }
  .sidebar-search {
    min-height: 44px;
    margin: 0 max(12px, env(safe-area-inset-right)) 8px max(12px, env(safe-area-inset-left));
  }
  .conversation-list {
    padding-right: max(8px, env(safe-area-inset-right));
    padding-left: max(8px, env(safe-area-inset-left));
    overscroll-behavior: contain;
  }
  .conversation-item { min-height: 72px; }
}
</style>
