<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import type { BroadcastCompletePayload, EmergencyBroadcast, FileUpload } from '../../types'
import { api } from '../../services/api'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  broadcast: EmergencyBroadcast
  submitting?: boolean
}

const props = withDefaults(defineProps<Props>(), { submitting: false })
const emit = defineEmits<{ complete: [payload: BroadcastCompletePayload] }>()

const uploads = shallowRef<FileUpload[]>([])
const location = shallowRef<BroadcastCompletePayload['location']>()
const uploading = shallowRef(false)
const locating = shallowRef(false)
const error = shallowRef('')
const ready = computed(() => !uploading.value && !locating.value && !props.submitting
  && (!props.broadcast.requireImageProof || uploads.value.length > 0)
  && (!props.broadcast.requireLocationProof || Boolean(location.value)))
const needsEvidence = computed(() => (
  props.broadcast.requireImageProof || props.broadcast.requireLocationProof
))
const completionButtonCopy = computed(() => {
  if (props.submitting) return '正在提交完成结果…'
  if (!ready.value) return '请先完成所需步骤'
  return '确认完成任务'
})
const completionHint = computed(() => {
  if (!needsEvidence.value) return '确认后将把你的处理状态更新为“已执行”。'
  if (!ready.value) return '完成以上必填步骤后，才能提交任务完成结果。'
  return '所需材料已齐全，可以确认完成任务。'
})

async function upload(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? []).slice(0, 3 - uploads.value.length)
  if (!files.length) return
  uploading.value = true
  error.value = ''
  try {
    const added = await Promise.all(files.map((file) => {
      if (!file.type.startsWith('image/')) throw new Error('只能上传图片文件')
      return api.files.uploadBroadcastImage(file)
    }))
    uploads.value = [...uploads.value, ...added]
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片上传失败'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function captureLocation(): void {
  if (!navigator.geolocation) {
    error.value = '当前浏览器不支持定位'
    return
  }
  locating.value = true
  error.value = ''
  navigator.geolocation.getCurrentPosition(
    (position) => {
      location.value = {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracyMeters: position.coords.accuracy,
        capturedAt: new Date(position.timestamp).toISOString(),
      }
      locating.value = false
    },
    (cause) => {
      error.value = cause.message || '无法获取当前位置'
      locating.value = false
    },
    { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
  )
}
</script>

<template>
  <section class="completion-panel" aria-labelledby="completion-title">
    <header class="completion-header">
      <span class="completion-mark" aria-hidden="true"><UiIcon name="check" :size="17" /></span>
      <div>
        <p class="completion-kicker">下一步</p>
        <h3 id="completion-title" class="completion-title">完成任务</h3>
      </div>
    </header>

    <div class="completion-requirements">
      <div v-if="!needsEvidence" class="requirement-row requirement-row--ready">
        <span class="requirement-icon"><UiIcon name="check" :size="15" /></span>
        <div class="requirement-copy">
          <strong>确认已完成任务</strong>
          <span>无需提交额外图片或定位。</span>
        </div>
        <span class="requirement-state">可提交</span>
      </div>

      <div v-if="broadcast.requireImageProof" class="requirement-row" :class="{ 'requirement-row--ready': uploads.length > 0 }">
        <span class="requirement-icon"><UiIcon name="image" :size="15" /></span>
        <div class="requirement-copy">
          <strong>完成图片</strong>
          <span>{{ uploads.length ? `已添加 ${uploads.length} 张图片` : '至少上传 1 张现场图片' }}</span>
        </div>
        <label class="evidence-button">
          {{ uploading ? '上传中…' : uploads.length ? '继续添加' : '上传图片' }}
          <input hidden type="file" accept="image/*" multiple :disabled="uploading || uploads.length >= 3" @change="upload">
        </label>
      </div>

      <div v-if="uploads.length" class="upload-list">
        <span v-for="image in uploads" :key="image.id" class="upload-item">
          {{ image.originalName }}
          <button type="button" aria-label="移除图片" @click="uploads = uploads.filter((item) => item.id !== image.id)">×</button>
        </span>
      </div>

      <div v-if="broadcast.requireLocationProof" class="requirement-row" :class="{ 'requirement-row--ready': location }">
        <span class="requirement-icon"><UiIcon name="pin" :size="15" /></span>
        <div class="requirement-copy">
          <strong>当前位置</strong>
          <span>{{ location ? `已获取位置，精度约 ${Math.round(location.accuracyMeters ?? 0)} 米` : '需要获取当前位置' }}</span>
        </div>
        <button class="evidence-button" type="button" :disabled="locating" @click="captureLocation">
          {{ locating ? '获取中…' : location ? '重新获取' : '获取位置' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="completion-error">{{ error }}</p>
    <button class="complete-button" type="button" :disabled="!ready" @click="emit('complete', { imageFileIds: uploads.map((item) => item.id), location })">
      <UiIcon name="check" :size="16" />
      {{ completionButtonCopy }}
    </button>
    <p class="completion-hint">{{ completionHint }}</p>
  </section>
</template>

<style scoped>
.completion-panel { display: grid; margin-top: 16px; padding: 15px; gap: 12px; border: 1px solid color-mix(in srgb, var(--green) 24%, var(--separator)); border-radius: 14px; background: color-mix(in srgb, var(--green) 4%, var(--surface)); }
.completion-header { display: flex; align-items: center; gap: 10px; }
.completion-mark { display: inline-grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 10px; color: var(--green); background: color-mix(in srgb, var(--green) 12%, var(--surface)); }
.completion-kicker { margin: 0 0 2px; color: var(--green); font-size: 10px; font-weight: 750; }
.completion-title { margin: 0; font-size: 15px; letter-spacing: -0.02em; }
.completion-requirements { display: grid; gap: 7px; }
.requirement-row { display: grid; min-height: 58px; padding: 9px 10px; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: center; gap: 9px; border: 1px solid var(--separator); border-radius: 10px; background: var(--surface); }
.requirement-row--ready { border-color: color-mix(in srgb, var(--green) 28%, var(--separator)); background: color-mix(in srgb, var(--green) 5%, var(--surface)); }
.requirement-icon { display: inline-grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; color: var(--ink-soft); background: var(--fill); }
.requirement-row--ready .requirement-icon { color: var(--green); background: color-mix(in srgb, var(--green) 12%, var(--surface)); }
.requirement-copy { display: grid; min-width: 0; gap: 2px; }
.requirement-copy strong { font-size: 12px; }
.requirement-copy span { overflow: hidden; color: var(--ink-soft); font-size: 10px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.requirement-state { color: var(--green); font-size: 10px; font-weight: 700; }
.evidence-button { display: inline-flex; min-height: 32px; padding: 0 10px; align-items: center; justify-content: center; border: 1px solid var(--separator); border-radius: 9px; color: var(--ink); font-size: 11px; font-weight: 650; line-height: 1; white-space: nowrap; background: var(--surface); cursor: pointer; }
.evidence-button:hover:not(:disabled) { border-color: color-mix(in srgb, var(--blue) 36%, var(--separator)); color: var(--blue); background: var(--active); }
.evidence-button:disabled, .complete-button:disabled { cursor: not-allowed; opacity: .55; }
.upload-list { display: flex; margin-top: -2px; flex-wrap: wrap; gap: 6px; }
.upload-item { display: inline-flex; max-width: 100%; padding: 4px 7px; align-items: center; gap: 5px; border-radius: 7px; color: var(--ink-soft); font-size: 11px; background: var(--fill); }
.upload-item button { display: inline-flex; width: 20px; height: 20px; padding: 0; align-items: center; justify-content: center; border: 0; border-radius: 6px; color: var(--coral); font-size: 16px; line-height: 1; background: transparent; cursor: pointer; }
.completion-error { margin: -2px 0 0; color: var(--coral); font-size: 11px; }
.complete-button { display: inline-flex; min-height: 42px; padding: 0 14px; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: 10px; color: white; font-size: 12px; font-weight: 700; line-height: 1; background: var(--green); box-shadow: 0 5px 12px color-mix(in srgb, var(--green) 22%, transparent); cursor: pointer; }
.complete-button:hover:not(:disabled) { background: color-mix(in srgb, var(--green) 86%, #000); }
.completion-hint { margin: -4px 0 0; color: var(--ink-soft); font-size: 10px; line-height: 1.45; text-align: center; }

@media (max-width: 520px) {
  .requirement-row { grid-template-columns: 30px minmax(0, 1fr); }
  .evidence-button { grid-column: 2; justify-self: start; }
  .requirement-state { grid-column: 2; justify-self: start; }
}
</style>
