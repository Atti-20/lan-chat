<script setup lang="ts">
import { computed } from 'vue'
import type { ChatSection } from '../../composables/useChat'
import UiIcon, { type IconName } from '../base/UiIcon.vue'

interface Props {
  section: ChatSection
}

const props = defineProps<Props>()
const emit = defineEmits<{
  primary: [section: ChatSection]
}>()

interface WelcomeContent {
  kicker: string
  title: string
  description: string
  action?: string
  icon: IconName
}

const welcomeBySection: Record<ChatSection, WelcomeContent> = {
  messages: {
    kicker: '消息中心',
    title: '选择一段对话',
    description: '从左侧打开最近会话，消息会从上次停下的位置继续。',
    action: '开始新对话',
    icon: 'messages',
  },
  contacts: {
    kicker: '好友通讯录',
    title: '选择一位好友',
    description: '左侧展示好友与待处理申请；选择好友后即可查看聊天记录。',
    action: '添加好友',
    icon: 'contacts',
  },
  groups: {
    kicker: '群组协作',
    title: '选择一个群组',
    description: '进入已有群组继续讨论，或创建一个新的协作空间。',
    action: '创建群聊',
    icon: 'groups',
  },
  admin: {
    kicker: '节点控制台',
    title: '选择管理模块',
    description: '从左侧进入账号管理、连接诊断或运行日志；管理内容会在这里独立呈现。',
    icon: 'admin',
  },
}
const content = computed(() => welcomeBySection[props.section])
</script>

<template>
  <section class="workspace-welcome" :data-section="section" aria-live="polite">
    <div class="welcome-panel">
      <div class="welcome-signal" aria-hidden="true">
        <span class="signal-orbit" />
        <span class="signal-core"><UiIcon :name="content.icon" :size="29" /></span>
        <i /><i /><i />
      </div>
      <p>{{ content.kicker }}</p>
      <h2>{{ content.title }}</h2>
      <span>{{ content.description }}</span>
      <button v-if="content.action" class="secondary-button" type="button" @click="emit('primary', section)">
        {{ content.action }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.workspace-welcome {
  display: grid;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  padding: 36px;
  grid-template-rows: minmax(0, 1fr);
  place-content: center;
  place-items: center;
  color: var(--ink);
  text-align: center;
  background: var(--surface);
}
.welcome-panel {
  display: grid;
  width: min(100%, 390px);
  padding: 28px 32px 30px;
  place-items: center;
  border: 1px solid var(--separator);
  border-radius: 26px;
  background: var(--surface-raise);
  box-shadow: 0 14px 36px color-mix(in srgb, var(--shadow-color) 55%, transparent), inset 0 1px 0 var(--highlight-soft);
}
.welcome-signal { position: relative; display: grid; width: 92px; height: 92px; margin-bottom: 13px; place-items: center; }
.signal-orbit { position: absolute; inset: 5px; border: 1px solid color-mix(in srgb, var(--blue) 16%, transparent); border-radius: 43% 57% 52% 48%; transform: rotate(-11deg); }
.signal-core { display: grid; width: 60px; height: 60px; place-items: center; border-radius: 19px; color: var(--blue); background: var(--fill); box-shadow: inset 0 1px 0 var(--highlight-soft); }
.welcome-signal i { position: absolute; width: 7px; height: 7px; border: 2px solid var(--surface-raise); border-radius: 50%; background: var(--blue); }
.welcome-signal i:nth-of-type(1) { top: 8px; right: 14px; }
.welcome-signal i:nth-of-type(2) { bottom: 10px; left: 11px; background: var(--green); }
.welcome-signal i:nth-of-type(3) { right: 3px; bottom: 25px; background: var(--violet); }
.welcome-panel > p { margin: 0 0 6px; color: var(--blue); font-size: 10px; font-weight: 700; letter-spacing: .08em; }
.welcome-panel > h2 { margin: 0; font-size: 21px; letter-spacing: -.04em; }
.welcome-panel > span { max-width: 320px; margin: 8px 0 15px; color: var(--ink-soft); font-size: 12px; line-height: 1.6; }
.workspace-welcome[data-section="contacts"] .signal-core { color: var(--violet); }
.workspace-welcome[data-section="groups"] .signal-core { color: var(--green); }
.workspace-welcome[data-section="admin"] .signal-core { color: #d97706; }
.workspace-welcome .secondary-button:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 55%, transparent); outline-offset: 3px; }

@media (prefers-reduced-motion: no-preference) {
  .signal-orbit { animation: signal-drift 9s ease-in-out infinite alternate; }
  @keyframes signal-drift { to { transform: rotate(18deg) scale(1.035); } }
}
@media (max-width: 760px) {
  .workspace-welcome { padding: 26px 22px; }
  .welcome-panel { padding: 24px 24px 26px; border-radius: 22px; }
}
</style>
