<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue'
import type { TemporaryRoomCreatePayload, TemporaryRoomExpiryAction } from '../../types'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  open: boolean
  saving?: boolean
}

type TemporaryRoomFormState = Omit<TemporaryRoomCreatePayload, 'purpose'> & { purpose: string }

const props = withDefaults(defineProps<Props>(), { saving: false })
const emit = defineEmits<{
  close: []
  create: [payload: TemporaryRoomCreatePayload]
}>()

const showValidation = shallowRef(false)
const minimumExpiry = shallowRef(toLocalDateTime(new Date(Date.now() + 60_000)))
const form = reactive<TemporaryRoomFormState>(defaultPayload())

const expiryActions: readonly {
  value: TemporaryRoomExpiryAction
  label: string
  detail: string
}[] = [
  { value: 'FREEZE', label: '冻结', detail: '到期后保留内容，只允许查看' },
  { value: 'ARCHIVE', label: '归档', detail: '长期保留为只读协作记录' },
  { value: 'DESTROY', label: '销毁', detail: '到期后撤销成员访问权限' },
]

const validationMessage = computed(validateForm)
const visibleError = computed(() => showValidation.value ? validationMessage.value : '')
const canSubmit = computed(() => !props.saving && validationMessage.value === '')

watch(() => props.open, (open) => {
  if (open) {
    minimumExpiry.value = toLocalDateTime(new Date(Date.now() + 60_000))
    Object.assign(form, defaultPayload())
  }
  showValidation.value = false
}, { immediate: true })

function defaultPayload(): TemporaryRoomFormState {
  return {
    roomName: '',
    purpose: '',
    expiresAt: toLocalDateTime(new Date(Date.now() + 24 * 60 * 60 * 1000)),
    maxMembers: 50,
    allowGuests: false,
    allowMemberInvite: true,
    allowFileUpload: true,
    allowFileDownload: true,
    allowForward: false,
    messageRetentionDays: 7,
    allowExternalSync: false,
    expireAction: 'FREEZE',
  }
}

function toLocalDateTime(date: Date): string {
  const localTime = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return localTime.toISOString().slice(0, 16)
}

function validateForm(): string {
  const roomName = form.roomName.trim()
  if (roomName.length < 2 || roomName.length > 50) return '房间名称需为 2–50 个字符。'
  if (form.purpose.trim().length > 500) return '使用目的不能超过 500 个字符。'
  const expiry = new Date(form.expiresAt).getTime()
  if (!Number.isFinite(expiry) || expiry <= Date.now()) return '请选择晚于当前时间的有效期。'
  if (!Number.isInteger(form.maxMembers) || form.maxMembers < 2 || form.maxMembers > 200) {
    return '成员上限需为 2–200 人。'
  }
  if (!Number.isInteger(form.messageRetentionDays)
    || form.messageRetentionDays < 1
    || form.messageRetentionDays > 365) {
    return '消息保存期限需为 1–365 天。'
  }
  return ''
}

function requestClose(): void {
  if (!props.saving) emit('close')
}

function submit(): void {
  showValidation.value = true
  if (validateForm() || props.saving) return
  emit('create', {
    ...form,
    roomName: form.roomName.trim(),
    purpose: form.purpose.trim(),
  })
}
</script>

<template>
  <div
    v-if="open"
    class="modal-backdrop"
    role="presentation"
    @click.self="requestClose"
    @keydown.esc="requestClose"
  >
    <section
      class="room-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="temporary-room-title"
      aria-describedby="temporary-room-description"
    >
      <header class="sheet-header">
        <div class="title-mark" aria-hidden="true">
          <UiIcon name="groups" :size="21" />
        </div>
        <div class="title-copy">
          <p>TIME-BOXED SPACE</p>
          <h2 id="temporary-room-title">创建临时协作房间</h2>
          <span id="temporary-room-description">设置成员范围、协作权限与到期处理方式。</span>
        </div>
        <button
          class="close-button"
          type="button"
          aria-label="关闭"
          :disabled="saving"
          @click="requestClose"
        >
          <UiIcon name="close" :size="16" />
        </button>
      </header>

      <form class="room-form" novalidate @submit.prevent="submit">
        <div class="form-scroll">
          <section class="form-section" aria-labelledby="room-basics-heading">
            <div class="section-heading">
              <span>01</span>
              <div>
                <h3 id="room-basics-heading">房间信息</h3>
                <p>用于成员快速识别这次临时协作。</p>
              </div>
            </div>

            <div class="field-grid">
              <label class="field-group field-group--wide">
                <span>房间名称</span>
                <input
                  v-model="form.roomName"
                  class="field"
                  autofocus
                  maxlength="50"
                  placeholder="例如：展会现场协作"
                />
              </label>

              <label class="field-group field-group--wide">
                <span>使用目的 <small>可选</small></span>
                <textarea
                  v-model="form.purpose"
                  class="field purpose-field"
                  maxlength="500"
                  rows="3"
                  placeholder="简要说明目标、交接事项或现场规则"
                />
                <small class="character-count">{{ form.purpose.length }} / 500</small>
              </label>

              <label class="field-group">
                <span>有效期至</span>
                <input
                  v-model="form.expiresAt"
                  class="field"
                  type="datetime-local"
                  :min="minimumExpiry"
                />
              </label>

              <label class="field-group">
                <span>成员上限</span>
                <input
                  v-model.number="form.maxMembers"
                  class="field"
                  type="number"
                  min="2"
                  max="200"
                  inputmode="numeric"
                />
              </label>
            </div>
          </section>

          <section class="form-section" aria-labelledby="room-permissions-heading">
            <div class="section-heading">
              <span>02</span>
              <div>
                <h3 id="room-permissions-heading">协作权限</h3>
                <p>权限最终由节点在每次操作时重新校验。</p>
              </div>
            </div>

            <div class="policy-grid">
              <label class="policy-option">
                <input v-model="form.allowGuests" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>允许访客</strong><small>访客账号可凭房间码加入</small></span>
              </label>
              <label class="policy-option">
                <input v-model="form.allowMemberInvite" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>成员可邀请</strong><small>成员可以分享房间码</small></span>
              </label>
              <label class="policy-option">
                <input v-model="form.allowFileUpload" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>允许上传</strong><small>成员可以向房间发送附件</small></span>
              </label>
              <label class="policy-option">
                <input v-model="form.allowFileDownload" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>允许下载</strong><small>成员可以保存房间文件</small></span>
              </label>
              <label class="policy-option">
                <input v-model="form.allowForward" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>允许转发</strong><small>内容可转发至其他会话</small></span>
              </label>
              <label class="policy-option">
                <input v-model="form.allowExternalSync" type="checkbox" />
                <span class="toggle" aria-hidden="true"><i /></span>
                <span class="policy-copy"><strong>允许外部同步</strong><small>可同步至授权的外部节点</small></span>
              </label>
            </div>
          </section>

          <section class="form-section" aria-labelledby="room-lifecycle-heading">
            <div class="section-heading">
              <span>03</span>
              <div>
                <h3 id="room-lifecycle-heading">数据生命周期</h3>
                <p>房间到期后将自动执行所选策略。</p>
              </div>
            </div>

            <label class="field-group retention-field">
              <span>消息保存天数</span>
              <input
                v-model.number="form.messageRetentionDays"
                class="field"
                type="number"
                min="1"
                max="365"
                inputmode="numeric"
              />
            </label>

            <fieldset class="expiry-options">
              <legend>到期处理</legend>
              <label
                v-for="option in expiryActions"
                :key="option.value"
                class="expiry-option"
                :class="{ 'expiry-option--selected': form.expireAction === option.value }"
              >
                <input v-model="form.expireAction" type="radio" :value="option.value" />
                <span class="radio-mark" aria-hidden="true" />
                <span><strong>{{ option.label }}</strong><small>{{ option.detail }}</small></span>
              </label>
            </fieldset>
          </section>
        </div>

        <footer class="sheet-footer">
          <p v-if="visibleError" class="form-error" role="alert">{{ visibleError }}</p>
          <p v-else class="privacy-note">房间码仅应分享给本次协作成员。</p>
          <div class="footer-actions">
            <button class="secondary-button" type="button" :disabled="saving" @click="requestClose">取消</button>
            <button class="primary-button" type="submit" :disabled="!canSubmit">
              {{ saving ? '正在创建…' : '创建房间' }}
            </button>
          </div>
        </footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 105;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.room-sheet {
  display: grid;
  width: min(100%, 720px);
  max-height: calc(100dvh - 40px);
  grid-template-rows: auto minmax(0, 1fr);
  border: 1px solid var(--glass-border);
  border-radius: 24px;
  overflow: hidden;
  background: var(--surface-raise);
  box-shadow: 0 24px 70px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  backdrop-filter: blur(22px) saturate(150%);
  -webkit-backdrop-filter: blur(22px) saturate(150%);
}

.sheet-header {
  display: grid;
  padding: 24px 24px 20px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid var(--separator);
}

.title-mark {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 15px 15px 15px 7px;
  color: var(--blue);
  background: var(--active);
  box-shadow: inset 0 1px 0 var(--highlight);
}
.title-mark .ui-icon { width: 21px; }

.title-copy { min-width: 0; }
.title-copy p {
  margin: 0 0 3px;
  color: var(--blue);
  font-family: "SF Mono", ui-monospace, monospace;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: .14em;
}
.title-copy h2 { margin: 0; font-size: 21px; letter-spacing: -.03em; }
.title-copy > span { display: block; margin-top: 5px; color: var(--ink-soft); font-size: 11px; line-height: 1.45; }

.close-button {
  display: grid;
  width: 34px;
  height: 34px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
}
.close-button:hover { background: var(--button-hover); }
.close-button:disabled { cursor: not-allowed; opacity: .45; }
.close-button .ui-icon { width: 16px; }

.room-form { display: grid; min-height: 0; grid-template-rows: minmax(0, 1fr) auto; }
.form-scroll { min-height: 0; padding: 2px 24px 22px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: var(--separator-strong) transparent; }
.form-section { padding: 22px 0; border-bottom: 1px solid var(--separator); }
.form-section:last-child { border-bottom: 0; }

.section-heading { display: flex; margin-bottom: 15px; align-items: flex-start; gap: 10px; }
.section-heading > span {
  display: grid;
  min-width: 28px;
  height: 22px;
  place-items: center;
  border-radius: 7px;
  color: var(--blue);
  font-family: "SF Mono", ui-monospace, monospace;
  font-size: 9px;
  font-weight: 700;
  background: var(--active);
}
.section-heading h3 { margin: 0; font-size: 14px; letter-spacing: -.01em; }
.section-heading p { margin: 3px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.45; }

.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.field-group { position: relative; display: grid; min-width: 0; gap: 7px; }
.field-group--wide { grid-column: 1 / -1; }
.field-group > span { color: var(--ink-soft); font-size: 11px; font-weight: 650; }
.field-group > span small { color: var(--ink-faint); font-size: 10px; font-weight: 500; }
.field { color-scheme: light dark; }
.purpose-field { min-height: 78px; padding-top: 12px; padding-bottom: 12px; resize: vertical; line-height: 1.5; }
.character-count { position: absolute; right: 10px; bottom: 7px; color: var(--ink-faint); font-size: 9px; }

.policy-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.policy-option {
  display: grid;
  min-height: 66px;
  padding: 11px 12px;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  border: 1px solid var(--separator);
  border-radius: 14px;
  background: var(--surface-glass);
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease;
}
.policy-option:hover { border-color: var(--separator-strong); background: var(--hover); }
.policy-option > input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.toggle {
  position: relative;
  width: 34px;
  height: 20px;
  border-radius: 999px;
  background: var(--separator-strong);
  transition: background-color 160ms ease;
}
.toggle i {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--surface);
  box-shadow: 0 1px 4px var(--shadow-color);
  transition: transform 180ms var(--ease-liquid);
}
.policy-option > input:checked + .toggle { background: var(--blue); }
.policy-option > input:checked + .toggle i { transform: translateX(14px); }
.policy-option > input:focus-visible + .toggle { outline: 3px solid color-mix(in srgb, var(--blue) 22%, transparent); outline-offset: 2px; }
.policy-copy { display: grid; min-width: 0; gap: 3px; }
.policy-copy strong { font-size: 12px; }
.policy-copy small { color: var(--ink-soft); font-size: 10px; line-height: 1.35; }

.retention-field { width: min(100%, 220px); margin-bottom: 16px; }
.expiry-options { display: grid; padding: 0; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; border: 0; }
.expiry-options legend { padding: 0 0 8px; color: var(--ink-soft); font-size: 11px; font-weight: 650; }
.expiry-option {
  display: grid;
  min-height: 78px;
  padding: 11px;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: flex-start;
  gap: 9px;
  border: 1px solid var(--separator);
  border-radius: 14px;
  background: var(--surface-glass);
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease;
}
.expiry-option:hover { background: var(--hover); }
.expiry-option--selected { border-color: color-mix(in srgb, var(--blue) 42%, var(--separator)); background: var(--active); }
.expiry-option > input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.radio-mark { display: grid; width: 17px; height: 17px; margin-top: 1px; place-items: center; border: 2px solid var(--separator-strong); border-radius: 50%; }
.radio-mark::after { width: 7px; height: 7px; border-radius: 50%; background: var(--blue); content: ""; opacity: 0; transform: scale(.5); transition: 150ms ease; }
.expiry-option > input:checked + .radio-mark { border-color: var(--blue); }
.expiry-option > input:checked + .radio-mark::after { opacity: 1; transform: scale(1); }
.expiry-option > input:focus-visible + .radio-mark { outline: 3px solid color-mix(in srgb, var(--blue) 22%, transparent); outline-offset: 2px; }
.expiry-option > span:last-child { display: grid; gap: 4px; }
.expiry-option strong { font-size: 12px; }
.expiry-option small { color: var(--ink-soft); font-size: 10px; line-height: 1.35; }

.sheet-footer {
  display: flex;
  min-height: 72px;
  padding: 13px 24px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-top: 1px solid var(--separator);
  background: var(--surface-glass);
}
.privacy-note,
.form-error { margin: 0; font-size: 10px; line-height: 1.4; }
.privacy-note { color: var(--ink-faint); }
.form-error { color: var(--coral); }
.footer-actions { display: flex; flex: 0 0 auto; gap: 9px; }
.footer-actions .secondary-button,
.footer-actions .primary-button { min-width: 104px; }

@media (max-width: 640px) {
  .modal-backdrop { padding: 10px; place-items: end center; }
  .room-sheet { width: 100%; max-height: calc(100dvh - 20px); border-radius: 24px 24px 18px 18px; }
  .sheet-header { padding: 19px 17px 16px; }
  .title-mark { width: 40px; height: 40px; }
  .title-copy h2 { font-size: 18px; }
  .title-copy > span { display: none; }
  .form-scroll { padding: 0 17px 18px; }
  .field-grid,
  .policy-grid,
  .expiry-options { grid-template-columns: minmax(0, 1fr); }
  .field-group--wide { grid-column: auto; }
  .expiry-option { min-height: 64px; }
  .sheet-footer { display: grid; padding: 12px 17px 15px; gap: 8px; }
  .footer-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .footer-actions .secondary-button,
  .footer-actions .primary-button { min-width: 0; }
}
</style>
