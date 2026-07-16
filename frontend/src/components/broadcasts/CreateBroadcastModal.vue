<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue'
import type {
  BroadcastCreatePayload,
  BroadcastPriority,
  BroadcastScopeType,
  Friend,
} from '../../types'
import UiIcon from '../base/UiIcon.vue'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  open: boolean
  friends: readonly Friend[]
  isAdmin?: boolean
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isAdmin: false,
  saving: false,
})
const emit = defineEmits<{
  close: []
  create: [payload: BroadcastCreatePayload]
}>()

interface FormState {
  title: string
  content: string
  priority: BroadcastPriority
  scopeType: BroadcastScopeType
  receiverIds: number[]
  confirmationRequired: boolean
  confirmationOptions: string[]
  deadlineAt: string
  bypassMute: boolean
  repeatReminder: boolean
}

const priorityOptions: readonly {
  value: BroadcastPriority
  label: string
  detail: string
}[] = [
  { value: 'NORMAL', label: '普通', detail: '进入广播列表' },
  { value: 'IMPORTANT', label: '重要', detail: '突出提醒成员' },
  { value: 'EMERGENCY', label: '紧急', detail: '立即要求关注' },
]
const confirmationChoices: readonly { value: string; label: string }[] = [
  { value: 'RECEIVED', label: '已收到' },
  { value: 'EXECUTED', label: '已执行' },
  { value: 'NEED_SUPPORT', label: '需要支援' },
]

const form = reactive<FormState>(initialForm())
const attempted = shallowRef(false)
const minimumDeadline = shallowRef('')

const scopeOptions = computed<readonly { value: BroadcastScopeType; label: string; detail: string }[]>(() => {
  const users = {
    value: 'USERS' as const,
    label: '指定好友',
    detail: '从自己的好友中选择接收者',
  }
  if (!props.isAdmin) return [users]
  return [
    { value: 'ALL' as const, label: '全体', detail: '发送给全部有效普通账号' },
    users,
  ]
})
const recipientFriends = computed(() => props.friends.filter((friend) => (
  friend.username !== 'admin' && friend.status !== 0
)))
const selectedFriendCount = computed(() => form.receiverIds.length)
const validationMessage = computed(() => {
  const title = form.title.trim()
  const content = form.content.trim()
  if (!title) return '请输入广播标题'
  if (title.length > 100) return '广播标题不能超过 100 个字符'
  if (!content) return '请输入广播内容'
  if (content.length > 10000) return '广播内容不能超过 10000 个字符'
  if (!props.isAdmin && form.scopeType !== 'USERS') return '当前账号只能向自己的好友发布广播'
  if (form.scopeType === 'ALL' && !props.isAdmin) return '只有管理员可以向全体成员发布广播'
  if (form.scopeType === 'USERS' && form.receiverIds.length === 0) return '请至少选择一名接收者'
  if (form.confirmationRequired && form.confirmationOptions.length === 0) return '请至少保留一个确认选项'
  if (form.deadlineAt && Date.parse(form.deadlineAt) <= Date.now()) return '截止时间必须晚于当前时间'
  return ''
})
const visibleError = computed(() => attempted.value ? validationMessage.value : '')

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(form, initialForm())
  minimumDeadline.value = toLocalDateTimeInput(new Date(Date.now() + 5 * 60 * 1000))
  attempted.value = false
}, { immediate: true })

watch(
  () => recipientFriends.value.map((friend) => friend.friendId),
  (friendIds) => {
    const visibleIds = new Set(friendIds)
    form.receiverIds = form.receiverIds.filter((userId) => visibleIds.has(userId))
  },
  { immediate: true },
)

function initialForm(): FormState {
  return {
    title: '',
    content: '',
    priority: 'IMPORTANT',
    scopeType: props.isAdmin ? 'ALL' : 'USERS',
    receiverIds: [],
    confirmationRequired: true,
    confirmationOptions: confirmationChoices.map((choice) => choice.value),
    deadlineAt: '',
    bypassMute: false,
    repeatReminder: false,
  }
}

function toLocalDateTimeInput(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function selectScope(scope: BroadcastScopeType): void {
  form.scopeType = scope
}

function toggleReceiver(userId: number): void {
  form.receiverIds = form.receiverIds.includes(userId)
    ? form.receiverIds.filter((value) => value !== userId)
    : [...form.receiverIds, userId]
}

function toggleConfirmationOption(option: string): void {
  form.confirmationOptions = form.confirmationOptions.includes(option)
    ? form.confirmationOptions.filter((value) => value !== option)
    : [...form.confirmationOptions, option]
}

function submit(): void {
  attempted.value = true
  if (validationMessage.value || props.saving) return

  const payload: BroadcastCreatePayload = {
    title: form.title.trim(),
    content: form.content.trim(),
    priority: form.priority,
    scopeType: form.scopeType,
    receiverIds: form.scopeType === 'USERS' ? [...form.receiverIds] : undefined,
    confirmationRequired: form.confirmationRequired,
    confirmationOptions: form.confirmationRequired ? [...form.confirmationOptions] : undefined,
    deadlineAt: form.deadlineAt || undefined,
    bypassMute: form.priority === 'EMERGENCY' && form.bypassMute,
    repeatReminder: form.confirmationRequired && form.repeatReminder,
  }
  emit('create', payload)
}
</script>

<template>
  <div v-if="open" class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="broadcast-sheet" role="dialog" aria-modal="true" aria-labelledby="broadcast-create-title">
      <header class="sheet-header">
        <span class="header-signal" :data-priority="form.priority" aria-hidden="true">
          <UiIcon name="bell" :size="21" />
        </span>
        <div class="header-copy">
          <p class="header-kicker">发布通知</p>
          <h2 id="broadcast-create-title" class="header-title">创建应急广播</h2>
        </div>
        <button class="close-button" type="button" aria-label="关闭" @click="emit('close')">
          <UiIcon name="close" :size="16" />
        </button>
      </header>

      <form class="broadcast-form" @submit.prevent="submit">
        <fieldset class="form-section">
          <legend class="section-title">优先级</legend>
          <div class="priority-grid" role="radiogroup" aria-label="广播优先级">
            <button
              v-for="option in priorityOptions"
              :key="option.value"
              class="priority-option"
              :class="{ 'priority-option--selected': form.priority === option.value }"
              :data-priority="option.value"
              type="button"
              role="radio"
              :aria-checked="form.priority === option.value"
              @click="form.priority = option.value"
            >
              <span class="option-signal" />
              <strong>{{ option.label }}</strong>
              <small>{{ option.detail }}</small>
            </button>
          </div>
        </fieldset>

        <div class="form-section message-fields">
          <label class="field-label">
            <span>标题</span>
            <input v-model="form.title" class="field" autofocus maxlength="100" placeholder="例如：园区临时疏散通知" />
          </label>
          <label class="field-label">
            <span>内容</span>
            <textarea
              v-model="form.content"
              class="field content-field"
              maxlength="10000"
              rows="5"
              placeholder="说明发生了什么、成员需要做什么，以及在哪里回报。"
            />
          </label>
        </div>

        <fieldset class="form-section">
          <legend class="section-title">接收范围</legend>
          <div class="scope-grid" role="radiogroup" aria-label="广播接收范围">
            <button
              v-for="option in scopeOptions"
              :key="option.value"
              class="scope-option"
              :class="{ 'scope-option--selected': form.scopeType === option.value }"
              type="button"
              role="radio"
              :aria-checked="form.scopeType === option.value"
              @click="selectScope(option.value)"
            >
              <strong>{{ option.label }}</strong>
              <small>{{ option.detail }}</small>
            </button>
          </div>

          <div v-if="form.scopeType === 'USERS'" class="recipient-picker">
            <div class="picker-heading">
              <span>选择好友 <small>团队成员后续开放</small></span>
              <strong>已选 {{ selectedFriendCount }} 人</strong>
            </div>
            <div class="friend-list">
              <button
                v-for="friend in recipientFriends"
                :key="friend.friendId"
                class="friend-row"
                :class="{ 'friend-row--selected': form.receiverIds.includes(friend.friendId) }"
                type="button"
                :aria-pressed="form.receiverIds.includes(friend.friendId)"
                @click="toggleReceiver(friend.friendId)"
              >
                <UserAvatar :name="friend.remark || friend.nickname" :avatar="friend.avatar" :size="36" />
                <span class="friend-name">{{ friend.remark || friend.nickname }}</span>
                <span class="selection-box">
                  <UiIcon v-if="form.receiverIds.includes(friend.friendId)" name="check" :size="13" />
                </span>
              </button>
              <p v-if="recipientFriends.length === 0" class="empty-friends">好友列表为空，暂时无法选择指定成员。</p>
            </div>
          </div>
        </fieldset>

        <fieldset class="form-section response-section">
          <legend class="section-title">回执与时限</legend>
          <label class="setting-row">
            <span class="setting-copy">
              <strong>要求成员确认</strong>
              <small>成员需要选择一项处理结果</small>
            </span>
            <input v-model="form.confirmationRequired" class="toggle-input" type="checkbox" />
          </label>

          <div v-if="form.confirmationRequired" class="confirmation-settings">
            <div class="confirmation-options" aria-label="允许的确认选项">
              <button
                v-for="choice in confirmationChoices"
                :key="choice.value"
                class="confirmation-option"
                :class="{ 'confirmation-option--selected': form.confirmationOptions.includes(choice.value) }"
                type="button"
                :aria-pressed="form.confirmationOptions.includes(choice.value)"
                @click="toggleConfirmationOption(choice.value)"
              >
                <UiIcon v-if="form.confirmationOptions.includes(choice.value)" name="check" :size="13" />
                {{ choice.label }}
              </button>
            </div>

            <label class="field-label deadline-field">
              <span>确认截止时间 <small>可选</small></span>
              <input v-model="form.deadlineAt" class="field" type="datetime-local" :min="minimumDeadline" />
            </label>

            <label class="setting-row compact-setting">
              <span class="setting-copy">
                <strong>重复提醒未确认成员</strong>
                <small>由服务端提醒策略决定频率</small>
              </span>
              <input v-model="form.repeatReminder" class="toggle-input" type="checkbox" />
            </label>
          </div>

          <label v-if="form.priority === 'EMERGENCY'" class="setting-row emergency-setting">
            <span class="setting-copy">
              <strong>绕过普通免打扰</strong>
              <small>仅用于确需立即触达的紧急情况</small>
            </span>
            <input v-model="form.bypassMute" class="toggle-input" type="checkbox" />
          </label>
        </fieldset>

        <p v-if="visibleError" class="form-error" role="alert">{{ visibleError }}</p>

        <footer class="form-actions">
          <button class="secondary-button" type="button" :disabled="saving" @click="emit('close')">取消</button>
          <button class="primary-button publish-button" type="submit" :disabled="saving">
            <UiIcon name="send" :size="16" />
            {{ saving ? '正在发布…' : '发布广播' }}
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 130;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.broadcast-sheet {
  display: flex;
  width: min(100%, 680px);
  max-height: calc(100dvh - 40px);
  flex-direction: column;
  border: 1px solid var(--glass-border);
  border-radius: 24px;
  color: var(--ink);
  background: var(--surface-raise);
  box-shadow: 0 24px 70px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow: hidden;
}

.sheet-header {
  display: flex;
  padding: 24px 26px 18px;
  align-items: center;
  gap: 13px;
  border-bottom: 1px solid var(--separator);
}

.header-signal {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 10%, var(--fill));
}

.header-signal[data-priority="IMPORTANT"] { color: #d97706; background: color-mix(in srgb, #d97706 11%, var(--fill)); }
.header-signal[data-priority="EMERGENCY"] { color: var(--coral); background: color-mix(in srgb, var(--coral) 11%, var(--fill)); }

.header-copy { min-width: 0; flex: 1; }
.header-kicker { margin: 0 0 3px; color: var(--ink-soft); font-size: 11px; font-weight: 650; }
.header-title { margin: 0; font-size: 21px; letter-spacing: -0.035em; }

.close-button {
  display: grid;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
}

.close-button:hover { background: var(--button-hover); }

.broadcast-form {
  min-height: 0;
  padding: 22px 26px 24px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--separator-strong) transparent;
}

.form-section {
  min-width: 0;
  margin: 0 0 22px;
  padding: 0;
  border: 0;
}

.section-title {
  width: 100%;
  margin: 0 0 9px;
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 650;
}

.priority-grid,
.scope-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.priority-option,
.scope-option {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 70px;
  padding: 12px 12px 11px;
  justify-items: start;
  gap: 4px;
  border: 1px solid var(--separator);
  border-radius: 14px;
  color: var(--ink);
  text-align: left;
  background: var(--surface-tint);
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease, transform 150ms var(--ease-liquid);
}

.priority-option:hover,
.scope-option:hover { border-color: var(--separator-strong); background: var(--hover); }
.priority-option:active,
.scope-option:active { transform: scale(0.985); }
.priority-option strong,
.scope-option strong { font-size: 13px; }
.priority-option small,
.scope-option small { color: var(--ink-soft); font-size: 10px; line-height: 1.4; }

.option-signal {
  position: absolute;
  top: 11px;
  right: 11px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--blue);
}

.priority-option[data-priority="IMPORTANT"] .option-signal { background: #d97706; }
.priority-option[data-priority="EMERGENCY"] .option-signal { background: var(--coral); }
.priority-option--selected,
.scope-option--selected { border-color: color-mix(in srgb, var(--blue) 48%, transparent); background: var(--active); }
.priority-option--selected[data-priority="IMPORTANT"] { border-color: color-mix(in srgb, #d97706 48%, transparent); background: color-mix(in srgb, #d97706 9%, transparent); }
.priority-option--selected[data-priority="EMERGENCY"] { border-color: color-mix(in srgb, var(--coral) 48%, transparent); background: color-mix(in srgb, var(--coral) 9%, transparent); }

.message-fields { display: grid; gap: 14px; }
.field-label { display: grid; gap: 7px; }
.field-label > span { color: var(--ink-soft); font-size: 12px; font-weight: 650; }
.content-field { min-height: 116px; padding-top: 12px; padding-bottom: 12px; line-height: 1.55; resize: vertical; }

.scope-detail { margin-top: 12px; }
.scope-select { appearance: auto; }
.field-help { color: var(--coral); font-size: 11px; }

.recipient-picker {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--separator);
  border-radius: 14px;
  background: var(--surface-tint);
}

.picker-heading { display: flex; margin-bottom: 7px; align-items: center; justify-content: space-between; color: var(--ink-soft); font-size: 11px; }
.picker-heading strong { color: var(--blue); font-weight: 650; }
.friend-list { display: grid; max-height: 210px; overflow-y: auto; }

.friend-row {
  display: flex;
  min-height: 50px;
  padding: 6px 8px;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 11px;
  color: var(--ink);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.friend-row:hover { background: var(--hover); }
.friend-row--selected { background: var(--active); }
.friend-name { min-width: 0; flex: 1; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.selection-box { display: grid; width: 21px; height: 21px; place-items: center; border: 1.5px solid var(--separator-strong); border-radius: 7px; }
.friend-row--selected .selection-box { border-color: var(--blue); color: #fff; background: var(--blue); }
.empty-friends { margin: 28px 12px; color: var(--ink-soft); font-size: 12px; text-align: center; }

.response-section {
  padding: 15px;
  border: 1px solid var(--separator);
  border-radius: 16px;
  background: var(--surface-tint);
}

.setting-row { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 18px; }
.setting-copy { display: grid; gap: 3px; }
.setting-copy strong { font-size: 13px; }
.setting-copy small { color: var(--ink-soft); font-size: 10px; line-height: 1.4; }

.toggle-input {
  width: 38px;
  height: 22px;
  flex: 0 0 auto;
  accent-color: var(--blue);
  cursor: pointer;
}

.confirmation-settings { display: grid; margin-top: 10px; padding-top: 13px; gap: 15px; border-top: 1px solid var(--separator); }
.confirmation-options { display: flex; flex-wrap: wrap; gap: 7px; }
.confirmation-option {
  display: inline-flex;
  min-height: 34px;
  padding: 0 11px;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--separator);
  border-radius: 10px;
  color: var(--ink-soft);
  font-size: 11px;
  font-weight: 600;
  background: var(--surface);
  cursor: pointer;
}
.confirmation-option--selected { border-color: color-mix(in srgb, var(--blue) 42%, transparent); color: var(--blue); background: var(--active); }
.deadline-field > span { display: flex; align-items: center; gap: 5px; }
.deadline-field > span small { color: var(--ink-faint); font-size: 10px; font-weight: 500; }
.compact-setting { min-height: 42px; }
.emergency-setting { margin-top: 11px; padding-top: 12px; border-top: 1px solid var(--separator); }
.emergency-setting .setting-copy strong { color: var(--coral); }
.emergency-setting .toggle-input { accent-color: var(--coral); }

.form-error { margin: -7px 0 16px; color: var(--coral); font-size: 12px; line-height: 1.5; }
.form-actions { display: flex; align-items: center; justify-content: flex-end; gap: 9px; }
.publish-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; }

@media (max-width: 620px) {
  .modal-backdrop { padding: 0; place-items: end center; }
  .broadcast-sheet { width: 100%; max-height: 94dvh; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 24px 24px 0 0; }
  .sheet-header { padding: 20px 18px 15px; }
  .broadcast-form { padding: 18px 18px max(22px, env(safe-area-inset-bottom)); }
  .priority-grid { grid-template-columns: 1fr; }
  .priority-option { min-height: 58px; }
  .scope-grid { grid-template-columns: 1fr; }
  .scope-option { min-height: 56px; }
  .form-actions { position: sticky; bottom: 0; padding-top: 12px; background: var(--surface-raise); }
  .form-actions .secondary-button,
  .form-actions .primary-button { flex: 1; }
}
</style>
