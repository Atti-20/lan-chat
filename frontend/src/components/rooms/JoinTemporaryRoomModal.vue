<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  open: boolean
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), { saving: false })
const emit = defineEmits<{
  close: []
  join: [roomCode: string]
}>()

const roomCode = shallowRef('')
const showValidation = shallowRef(false)
const codeModel = computed({
  get: () => roomCode.value,
  set: (value: string) => {
    roomCode.value = value.replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, 12)
  },
})
const codeIsValid = computed(() => /^[A-F0-9]{12}$/.test(roomCode.value))
const visibleError = computed(() => showValidation.value && !codeIsValid.value
  ? '请输入 12 位有效房间码。'
  : '')

watch(() => props.open, () => {
  roomCode.value = ''
  showValidation.value = false
}, { immediate: true })

function requestClose(): void {
  if (!props.saving) emit('close')
}

function submit(): void {
  showValidation.value = true
  if (!codeIsValid.value || props.saving) return
  emit('join', roomCode.value)
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
      class="join-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="join-room-title"
      aria-describedby="join-room-description"
    >
      <button
        class="close-button"
        type="button"
        aria-label="关闭"
        :disabled="saving"
        @click="requestClose"
      >
        <UiIcon name="close" :size="16" />
      </button>

      <div class="code-mark" aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
      </div>
      <p class="eyebrow">ROOM ACCESS</p>
      <h2 id="join-room-title">加入临时房间</h2>
      <p id="join-room-description" class="description">输入房间所有者分享的 12 位代码。</p>

      <form novalidate @submit.prevent="submit">
        <label class="code-field">
          <span class="sr-only">房间码</span>
          <input
            v-model="codeModel"
            autofocus
            autocomplete="one-time-code"
            inputmode="text"
            maxlength="12"
            placeholder="A1B2C3D4E5F6"
            spellcheck="false"
            :aria-invalid="showValidation && !codeIsValid"
            :aria-describedby="visibleError ? 'join-code-error' : 'join-code-hint'"
          />
        </label>
        <p v-if="visibleError" id="join-code-error" class="form-error" role="alert">{{ visibleError }}</p>
        <p v-else id="join-code-hint" class="code-hint">字母会自动转换为大写，空格与连字符会被忽略。</p>

        <div class="actions">
          <button class="secondary-button" type="button" :disabled="saving" @click="requestClose">取消</button>
          <button class="primary-button" type="submit" :disabled="!codeIsValid || saving">
            {{ saving ? '正在加入…' : '加入房间' }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 106;
  inset: 0;
  display: grid;
  padding: 20px;
  place-items: center;
  background: var(--backdrop);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.join-sheet {
  position: relative;
  display: grid;
  width: min(100%, 420px);
  padding: 34px 30px 26px;
  justify-items: center;
  border: 1px solid var(--glass-border);
  border-radius: 24px;
  overflow: hidden;
  text-align: center;
  background: var(--surface-raise);
  box-shadow: 0 24px 70px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  backdrop-filter: blur(22px) saturate(150%);
  -webkit-backdrop-filter: blur(22px) saturate(150%);
}

.join-sheet::before {
  position: absolute;
  top: -80px;
  left: 50%;
  width: 260px;
  height: 170px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--blue) 10%, transparent);
  content: "";
  filter: blur(12px);
  transform: translateX(-50%);
  pointer-events: none;
}

.close-button {
  position: absolute;
  z-index: 1;
  top: 14px;
  right: 14px;
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

.code-mark {
  position: relative;
  display: grid;
  width: 62px;
  height: 62px;
  margin-bottom: 16px;
  padding: 12px;
  grid-template-columns: repeat(2, 1fr);
  gap: 7px;
  border: 1px solid color-mix(in srgb, var(--blue) 24%, var(--separator));
  border-radius: 20px 20px 20px 8px;
  background: var(--active);
  box-shadow: inset 0 1px 0 var(--highlight), 0 10px 24px color-mix(in srgb, var(--blue) 12%, transparent);
}
.code-mark span { border: 2px solid var(--blue); border-radius: 4px; }
.code-mark span:nth-child(2),
.code-mark span:nth-child(3) { border-style: dashed; opacity: .7; }

.eyebrow {
  margin: 0 0 5px;
  color: var(--blue);
  font-family: "SF Mono", ui-monospace, monospace;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: .16em;
}
.join-sheet h2 { margin: 0; font-size: 22px; letter-spacing: -.035em; }
.description { margin: 7px 0 20px; color: var(--ink-soft); font-size: 12px; line-height: 1.5; }
.join-sheet form { display: grid; width: 100%; }

.code-field {
  display: flex;
  width: 100%;
  min-height: 58px;
  padding: 0 14px;
  align-items: center;
  border: 1px solid transparent;
  border-radius: 15px;
  background: var(--fill);
  transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease;
}
.code-field:focus-within {
  border-color: rgba(0, 122, 255, .45);
  background: var(--surface);
  box-shadow: 0 0 0 4px rgba(0, 122, 255, .08);
}
.code-field input {
  width: 100%;
  border: 0;
  color: var(--ink);
  font-family: "SF Mono", ui-monospace, monospace;
  font-size: clamp(17px, 5vw, 22px);
  font-weight: 750;
  letter-spacing: .14em;
  text-align: center;
  text-transform: uppercase;
  outline: none;
  background: transparent;
}
.code-field input::placeholder { color: var(--ink-faint); font-weight: 550; opacity: .55; }
.code-hint,
.form-error { min-height: 30px; margin: 8px 2px 0; font-size: 10px; line-height: 1.4; }
.code-hint { color: var(--ink-faint); }
.form-error { color: var(--coral); }

.actions { display: grid; margin-top: 10px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.actions .secondary-button,
.actions .primary-button { width: 100%; }

@media (max-width: 480px) {
  .modal-backdrop { padding: 10px; place-items: end center; }
  .join-sheet { width: 100%; padding: 31px 20px 22px; border-radius: 24px 24px 18px 18px; }
  .code-field input { letter-spacing: .09em; }
}
</style>
