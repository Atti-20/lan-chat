<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import type { ChatSection } from '../../composables/useChat'
import type { ChatMessage, Conversation, FriendRequest } from '../../types'
import { conversationPreview, formatMessageTime, formatTime } from '../../utils/format'
import UserAvatar from '../base/UserAvatar.vue'
import UiBadge from '../base/UiBadge.vue'
import UiIconButton from '../base/UiIconButton.vue'
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
const messageFilter = shallowRef<'all' | 'unread'>('all')
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

watch(() => props.section, () => {
  closeMoreMenu()
  messageFilter.value = 'all'
})
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
  contacts: '联系人',
  groups: '群聊',
  broadcasts: '广播',
  admin: '管理',
}[props.section]))
const searchPlaceholder = computed(() => ({
  messages: '搜索对话',
  contacts: '搜索联系人',
  groups: '搜索群聊',
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
  if (props.section === 'messages' && messageFilter.value === 'unread') return '暂时没有未读消息'
  if (props.section === 'admin') return '管理控制台已打开'
  if (props.section === 'groups') return '还没有加入群聊'
  if (props.section === 'contacts') return '搜索并添加第一位好友'
  return '新的对话会出现在这里'
})
const emptyDetail = computed(() => {
  if (query.value.trim()) return '调整关键词后再试一次。'
  if (props.section === 'messages' && messageFilter.value === 'unread') return '已读状态由服务端同步后更新。'
  if (props.section === 'groups') return '使用右上角按钮创建新的群聊。'
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
const unreadConversationCount = computed(() => props.conversations.filter((conversation) => (conversation.unreadCount ?? 0) > 0).length)
const filteredConversations = computed(() => messageFilter.value === 'unread'
  ? props.conversations.filter((conversation) => (conversation.unreadCount ?? 0) > 0)
  : props.conversations)
const listItems = computed<ConversationListItem[]>(() => {
  if (messageSearchMode.value) {
    return [
      ...matchingConversationItems.value,
      ...messageSearchItems.value.filter((item) => !searchConversationKeys.value.has(
        `${item.conversation.kind}-${item.conversation.id}`,
      )),
    ]
  }
  return filteredConversations.value.map((conversation) => ({
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
        <UiIconButton
          class="mini-button"
          name="plus"
          label="更多操作"
          size="compact"
          :aria-expanded="moreOpen"
          aria-haspopup="menu"
          @click="moreOpen = !moreOpen"
        />
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

    <div v-if="section === 'messages' && !messageSearchMode" class="conversation-filters" role="group" aria-label="消息筛选">
      <button
        class="filter-chip"
        :class="{ 'filter-chip--active': messageFilter === 'all' }"
        type="button"
        :aria-pressed="messageFilter === 'all'"
        @click="messageFilter = 'all'"
      >全部</button>
      <button
        class="filter-chip"
        :class="{ 'filter-chip--active': messageFilter === 'unread' }"
        type="button"
        :aria-pressed="messageFilter === 'unread'"
        @click="messageFilter = 'unread'"
      >未读 <UiBadge v-if="unreadConversationCount" class="filter-count" tone="pending" :value="unreadConversationCount" :label="`${unreadConversationCount} 个有未读消息的会话`" /></button>
    </div>

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
        :aria-current="selectedId === item.conversation.id && selectedKind === item.conversation.kind ? 'true' : undefined"
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
            <UiBadge
              v-if="!item.messageHit && item.conversation.unreadCount"
              class="unread-badge"
              tone="unread"
              :value="item.conversation.unreadCount > 99 ? '99+' : item.conversation.unreadCount"
              :label="`${item.conversation.unreadCount} 条未读消息`"
            />
            <UiBadge
              v-if="!item.messageHit && item.conversation.pendingCount"
              class="pending"
              tone="pending"
              :value="item.conversation.pendingCount"
              :label="`${item.conversation.pendingCount} 条待发送`"
            />
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
      <div v-else-if="messageSearchEmpty || (!messageSearchMode && listItems.length === 0)" class="empty-list">
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
.conversation-sidebar { display: flex; width: 330px; min-width: 0; min-height: 0; flex-direction: column; overflow: hidden; }
.sidebar-header { display: flex; align-items: center; justify-content: space-between; }
.sidebar-header p { margin: 0 0 2px; letter-spacing: .14em; text-transform: uppercase; }
.sidebar-header h1 { margin: 0; letter-spacing: -.04em; }
.header-actions { position: relative; display: flex; }
.action-menu {
  position: absolute;
  z-index: 12;
  top: calc(100% + 8px);
  right: 0;
  display: grid;
  min-width: 132px;
  padding: 5px;
  gap: 2px;
  border: 1px solid var(--mx-color-border-glass);
  border-radius: var(--mx-shape-radius-control);
  background: var(--mx-color-material-raised);
  box-shadow: 0 12px 28px var(--mx-color-shadow-default), inset 0 1px 0 var(--mx-color-highlight-soft);
}
.action-menu button {
  min-height: 38px;
  padding: 0 11px;
  border: 0;
  border-radius: var(--mx-shape-radius-small);
  color: var(--mx-color-text-primary);
  font: inherit;
  font-size: var(--mx-typography-caption-size);
  text-align: left;
  background: transparent;
  cursor: pointer;
}
.action-menu button:hover,
.action-menu button:focus-visible { color: var(--mx-color-action-text); background: var(--mx-color-interaction-hover); }
.action-menu button:focus-visible { outline: 2px solid color-mix(in srgb, var(--mx-color-brand-blue) 45%, transparent); outline-offset: -2px; }
.mini-button { border: 1px solid rgba(255,255,255,.75); }
.mini-button .ui-icon { width: 18px; }
.sidebar-search { display: flex; min-height: 42px; padding: 0 12px; align-items: center; gap: var(--mx-spacing-2); border: 1px solid rgba(144,169,194,.17); }
.sidebar-search .ui-icon { width: 17px; color: var(--mx-color-text-tertiary); }
.sidebar-search input { width: 100%; min-width: 0; border: 0; outline: none; color: var(--mx-color-text-primary); background: none; }
.sidebar-search input::-webkit-search-cancel-button { display: none; }
.sidebar-search kbd { padding: 3px 5px; border: 1px solid rgba(138,163,188,.2); border-radius: 5px; color: #8293a5; font-family: inherit; font-size: var(--mx-typography-micro-size); }
.conversation-filters { display: flex; padding: 10px 18px 8px; gap: var(--mx-spacing-2); }
.filter-chip { display: inline-flex; min-height: var(--mx-size-control-compact); padding: 0 var(--mx-spacing-2); align-items: center; gap: var(--mx-spacing-1); border: 0; border-radius: var(--mx-shape-radius-pill); color: var(--mx-color-text-secondary); font: inherit; font-size: var(--mx-typography-caption-size); font-weight: 650; background: var(--mx-color-background-fill); cursor: pointer; }
.filter-chip--active { color: var(--mx-color-action-text); background: var(--mx-color-interaction-selected); }
.filter-chip:focus-visible { outline: 2px solid var(--mx-color-focus-ring); outline-offset: 2px; }
.filter-count { min-width: 0; min-height: 0; padding-inline: 0; color: inherit; background: transparent; }
.conversation-list { display: flex; min-height: 0; flex: 1; flex-direction: column; overflow-y: auto; scrollbar-width: thin; scrollbar-color: rgba(92,124,156,.22) transparent; }
.conversation-item { display: flex; width: 100%; min-height: 74px; align-items: center; border: 1px solid transparent; text-align: left; background: transparent; cursor: pointer; transition: 200ms var(--mx-motion-easing-standard); }
.conversation-item:hover { transform: translateX(2px); }
.conversation-item--active { border-color: rgba(255,255,255,.82); }
.conversation-copy { display: grid; min-width: 0; flex: 1; }
.conversation-line { display: flex; min-width: 0; align-items: center; gap: var(--mx-spacing-2); }
.conversation-line strong { overflow: hidden; flex: 1; text-overflow: ellipsis; white-space: nowrap; }
.conversation-line time { color: var(--mx-color-text-tertiary); }
.conversation-preview > span { overflow: hidden; flex: 1; text-overflow: ellipsis; white-space: nowrap; }
.conversation-preview i { font-style: normal; }
.conversation-preview .unread-badge { flex: 0 0 auto; font-style: normal; }
.conversation-preview .pending { flex: 0 0 auto; font-style: normal; text-align: center; }
.conversation-pin { display: grid; width: 20px; height: 20px; margin-left: auto; place-items: center; color: var(--mx-color-action-text); }
.conversation-pin .ui-icon { width: 15px; height: 15px; }
.request-section { margin-bottom: 4px; }
.request-section-title { color: var(--mx-color-text-tertiary); font-size: var(--mx-typography-micro-size); font-weight: 700; }
.request-card { display: grid; grid-template-columns: auto 1fr; gap: 11px; border: 1px solid rgba(255,255,255,.68); }
.request-copy { min-width: 0; }
.request-copy strong { font-size: var(--mx-typography-body-small-size); }
.request-copy p { margin: 3px 0; overflow: hidden; color: var(--mx-color-text-secondary); font-size: var(--mx-typography-micro-size); text-overflow: ellipsis; white-space: nowrap; }
.request-copy span { color: var(--mx-color-text-tertiary); font-size: var(--mx-typography-micro-size); }
.request-actions { display: flex; grid-column: 1 / -1; gap: var(--mx-spacing-2); }
.request-actions button { min-height: 32px; padding: 0 13px; border: 0; border-radius: var(--mx-shape-radius-control); color: var(--mx-color-text-surface); font-size: var(--mx-typography-micro-size); font-weight: 700; background: var(--mx-color-background-fill); cursor: pointer; }
.request-actions .accept { color: white; background: var(--mx-color-action-primary); box-shadow: 0 6px 13px rgba(10,132,255,.2); }
.empty-list { display: grid; min-height: 260px; flex: 1; padding: 30px; place-items: center; align-content: center; text-align: center; }
.empty-list > span { display: grid; width: 52px; height: 52px; margin-bottom: 15px; place-items: center; border: 1px solid rgba(255,255,255,.8); border-radius: 19px; color: var(--mx-color-action-text); font-size: 23px; background: rgba(255,255,255,.48); box-shadow: inset 0 1px 0 #fff; }
.empty-list strong { font-size: var(--mx-typography-body-size); }
.empty-list p { margin: 6px 0 0; color: var(--mx-color-text-secondary); font-size: var(--mx-typography-micro-size); line-height: 1.5; }
.search-state { margin: 0; padding: 28px 16px; color: var(--mx-color-text-tertiary); font-size: var(--mx-typography-caption-size); line-height: 1.5; text-align: center; }
.search-state--error { color: var(--mx-color-status-danger); }
.sidebar-loading { display: grid; padding: 10px 18px; gap: 14px; }
.sidebar-loading span { height: 58px; background: linear-gradient(90deg, rgba(255,255,255,.18), rgba(255,255,255,.62), rgba(255,255,255,.18)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
@keyframes shimmer { to { background-position: -200% 0; } }

@media (max-width: 760px) {
  .conversation-sidebar { width: 100%; padding-bottom: 76px; }
}

.conversation-sidebar {
  width: 100%;
  border-right: 1px solid var(--mx-color-border-default);
  border-radius: 0;
  background: var(--mx-color-material-raised);
}
.sidebar-header { padding: 22px 18px 13px; }
.sidebar-header p {
  margin-bottom: 3px;
  color: var(--mx-color-action-text);
  font-family: inherit;
  font-size: var(--mx-typography-micro-size);
  font-weight: 600;
  letter-spacing: 0;
  text-transform: none;
}
.sidebar-header h1 { font-size: var(--mx-typography-headline-size); font-weight: 700; letter-spacing: -0.03em; }
.header-actions { gap: 5px; }
.mini-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  color: var(--mx-color-text-surface);
  background: var(--mx-color-background-fill);
  box-shadow: none;
}
.mini-button:hover { color: var(--mx-color-action-text); background: var(--mx-color-interaction-control-hover); }
.sidebar-search {
  min-height: 38px;
  margin: 0 12px 10px;
  border: 0;
  border-radius: var(--mx-shape-radius-control);
  background: var(--mx-color-background-fill);
}
.sidebar-search kbd { border-color: var(--mx-color-border-default); background: rgba(255, 255, 255, 0.58); }
.conversation-list { padding: 0 8px 12px; }
.conversation-item {
  min-height: 68px;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--mx-shape-radius-control);
  gap: 11px;
}
.conversation-item:hover { background: var(--mx-color-interaction-hover); transform: none; }
.conversation-item--active { background: var(--mx-color-interaction-selected); box-shadow: none; }
.conversation-copy { gap: 5px; }
.conversation-line strong { font-size: var(--mx-typography-body-size); }
.conversation-line time { font-size: var(--mx-typography-caption-size); }
.conversation-preview { color: var(--mx-color-text-tertiary); font-size: var(--mx-typography-caption-size); }
.request-section { margin-bottom: 6px; padding-bottom: 4px; border-bottom: 1px solid var(--mx-color-border-default); }
.request-section-title { padding: 8px 10px 4px; }
.request-card {
  padding: 12px 10px;
  border: 0;
  border-bottom: 1px solid var(--mx-color-border-default);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.empty-list > span,
.empty-list > .empty-icon {
  border: 0;
  border-radius: 50%;
  background: var(--mx-color-background-fill);
  box-shadow: none;
}
.empty-icon { display: grid; place-items: center; width: 52px; height: 52px; }
.empty-icon .ui-icon { width: 24px; color: var(--mx-color-action-text); }
.sidebar-loading span { height: 54px; border-radius: var(--mx-shape-radius-control); background-color: var(--mx-color-background-fill); }

@media (max-width: 760px) {
  .conversation-sidebar {
    width: 100%;
    padding-bottom: 0;
    border: 0;
    border-radius: 0;
    background: var(--mx-color-background-surface);
  }
  .sidebar-header {
    padding: max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) 12px max(16px, env(safe-area-inset-left));
  }
  .mini-button { width: 40px; height: 40px; }
  .sidebar-search {
    min-height: 44px;
    margin: 0 max(12px, env(safe-area-inset-right)) 8px max(12px, env(safe-area-inset-left));
  }
  .conversation-filters { padding-right: max(12px, env(safe-area-inset-right)); padding-left: max(12px, env(safe-area-inset-left)); }
  .conversation-list {
    padding-bottom: var(--mx-runtime-nav-occlusion, calc(88px + env(safe-area-inset-bottom)));
    padding-right: max(8px, env(safe-area-inset-right));
    padding-left: max(8px, env(safe-area-inset-left));
    overscroll-behavior: contain;
  }
  .conversation-item { min-height: 72px; }
}
</style>
