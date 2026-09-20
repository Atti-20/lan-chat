<script setup lang="ts">
import { computed, nextTick, shallowRef, useTemplateRef, watch } from 'vue'
import type { User } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'
import { api } from '../../services/api'
import { useToast } from '../../composables/useToast'
import {
  createTextAvatar,
  DEFAULT_TEXT_AVATAR_COLOR,
  isTextAvatar as isTextAvatarValue,
  TEXT_AVATAR_COLOR_PRESETS,
} from '../../services/textAvatar'

interface Props {
  open: boolean
  user: User
  saving?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
})
const emit = defineEmits<{
  close: []
  save: [payload: { nickname: string; avatar: string }]
}>()
const toast = useToast()
const nickname = shallowRef('')
const avatar = shallowRef('')
const uploadingAvatar = shallowRef(false)
const colorPresets = TEXT_AVATAR_COLOR_PRESETS

const currentColor = computed(() => {
  if (avatar.value?.startsWith('letter:')) {
    const parts = avatar.value.split(':')
    if (parts.length >= 3 && /^#[0-9a-f]{6}$/i.test(parts[2])) return parts[2]
  }
  return isTextAvatarValue(avatar.value) ? DEFAULT_TEXT_AVATAR_COLOR : ''
})

const isImageAvatar = computed(() => {
  return Boolean(avatar.value)
    && avatar.value !== 'text'
    && !avatar.value.startsWith('letter:')
    && !avatar.value.startsWith('emoji:')
    && !avatar.value.startsWith('svg:')
})
const isTextAvatar = computed(() =>
  isTextAvatarValue(avatar.value),
)
const textAvatarPreview = computed(() => createTextAvatar(
  nickname.value || props.user.nickname,
  currentColor.value || DEFAULT_TEXT_AVATAR_COLOR,
))
const previewAvatar = computed(() => isTextAvatar.value ? textAvatarPreview.value : avatar.value)

watch(() => [props.open, props.user.nickname, props.user.avatar] as const, ([open]) => {
  if (open) {
    nickname.value = props.user.nickname
    // 空头像代表文字头像，不能在资料弹窗中偷偷替换成气泡头像，
    // 否则个人资料与导航栏会显示成两个不同的头像。
    avatar.value = props.user.avatar?.startsWith('letter:')
      ? props.user.avatar
      : (isTextAvatarValue(props.user.avatar)
        || props.user.avatar?.startsWith('emoji:')
        || props.user.avatar?.startsWith('svg:'))
        ? createTextAvatar(props.user.nickname)
        : (props.user.avatar || createTextAvatar(props.user.nickname))
  }
}, { immediate: true })

function selectTextColor(color: string): void {
  avatar.value = createTextAvatar(nickname.value || props.user.nickname, color)
}

function selectTextAvatar(): void {
  avatar.value = createTextAvatar(nickname.value || props.user.nickname)
}

/* ===== 头像裁切相关 ===== */
const cropperVisible = shallowRef(false)
const cropPreviewUrl = shallowRef('')
const cropImgRef = useTemplateRef<HTMLImageElement>('cropImg')
const cropContainerRef = useTemplateRef<HTMLDivElement>('cropContainer')
// 裁切框状态（相对于显示图片的百分比 0~1）
const cropX = shallowRef(0)
const cropY = shallowRef(0)
const cropSize = shallowRef(0.8)
// 原始文件引用
let pendingFile: File | null = null
// 原始图片自然尺寸
const naturalW = shallowRef(0)
const naturalH = shallowRef(0)

function openCropper(file: File): void {
  pendingFile = file
  cropPreviewUrl.value = URL.createObjectURL(file)
  cropperVisible.value = true
  // 等 DOM 渲染完成后初始化裁切框位置
  nextTick(() => {
    const img = cropImgRef.value
    if (img && img.naturalWidth) {
      initCropBox(img.naturalWidth, img.naturalHeight)
    }
  })
}

function onCropImgLoad(): void {
  const img = cropImgRef.value
  if (!img) return
  naturalW.value = img.naturalWidth
  naturalH.value = img.naturalHeight
  initCropBox(img.naturalWidth, img.naturalHeight)
}

/**
 * 计算图片以 object-fit: contain 渲染在正方形容器中时的可见区域。
 * 返回值都是 0~1 的容器比例。
 */
function getImgBounds(w: number, h: number) {
  if (w >= h) {
    // 横图：宽度撑满，高度居中
    const imgH = h / w
    return { left: 0, top: (1 - imgH) / 2, width: 1, height: imgH }
  } else {
    // 竖图：高度撑满，宽度居中
    const imgW = w / h
    return { left: (1 - imgW) / 2, top: 0, width: imgW, height: 1 }
  }
}

function initCropBox(w: number, h: number): void {
  const bounds = getImgBounds(w, h)
  // 正方形裁切框占图片可见区域短边的 90%
  const side = Math.min(bounds.width, bounds.height) * 0.9
  cropSize.value = side
  // 在图片可见区域内居中
  cropX.value = bounds.left + (bounds.width - side) / 2
  cropY.value = bounds.top + (bounds.height - side) / 2
}

function closeCropper(): void {
  cropperVisible.value = false
  if (cropPreviewUrl.value) URL.revokeObjectURL(cropPreviewUrl.value)
  cropPreviewUrl.value = ''
  pendingFile = null
}

/* 拖拽裁切框——限制在图片可见区域内 */
function onCropPointerDown(e: PointerEvent): void {
  e.preventDefault()
  const container = cropContainerRef.value
  if (!container) return
  const rect = container.getBoundingClientRect()
  const startMX = e.clientX
  const startMY = e.clientY
  const startCX = cropX.value
  const startCY = cropY.value
  const bounds = getImgBounds(naturalW.value, naturalH.value)

  function onMove(ev: PointerEvent) {
    const dx = (ev.clientX - startMX) / rect.width
    const dy = (ev.clientY - startMY) / rect.height
    cropX.value = clamp(startCX + dx, bounds.left, bounds.left + bounds.width - cropSize.value)
    cropY.value = clamp(startCY + dy, bounds.top, bounds.top + bounds.height - cropSize.value)
  }
  function onUp() {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

/* 拖拽角点缩放——限制在图片可见区域内 */
function onResizePointerDown(e: PointerEvent): void {
  e.preventDefault()
  e.stopPropagation()
  const container = cropContainerRef.value
  if (!container) return
  const rect = container.getBoundingClientRect()
  const startMX = e.clientX
  const startMY = e.clientY
  const startSize = cropSize.value
  const startCX = cropX.value
  const startCY = cropY.value
  const bounds = getImgBounds(naturalW.value, naturalH.value)

  function onMove(ev: PointerEvent) {
    const dx = (ev.clientX - startMX) / rect.width
    const dy = (ev.clientY - startMY) / rect.height
    const delta = Math.max(dx, dy)
    // 最大不超过图片可见区域的右边界和下边界
    const maxSize = Math.min(
      bounds.left + bounds.width - startCX,
      bounds.top + bounds.height - startCY,
    )
    cropSize.value = clamp(startSize + delta, 0.15, maxSize)
  }
  function onUp() {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

/* 确认裁切并上传 */
async function confirmCrop(): Promise<void> {
  if (!pendingFile) return
  uploadingAvatar.value = true
  try {
    const blob = await cropImage(pendingFile, cropX.value, cropY.value, cropSize.value)
    const croppedFile = new File([blob], 'avatar.jpg', { type: 'image/jpeg' })
    const result = await api.files.uploadAvatar(croppedFile)
    const avatarUrl = result.thumbnailUrl || result.url
    avatar.value = avatarUrl
    toast.push('头像已上传', 'success', 1200)
    closeCropper()
  } catch {
    toast.push('头像上传失败', 'danger')
  } finally {
    uploadingAvatar.value = false
  }
}

function cropImage(file: File, rx: number, ry: number, rSize: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const w = img.naturalWidth
      const h = img.naturalHeight
      // rx, ry, rSize 是相对于正方形容器 (0~1) 的比例。
      // 图片以 object-fit: contain 渲染在正方形容器中，
      // 需要先算出图片在容器中的实际偏移和缩放，再转换到像素坐标。
      let imgLeft: number, imgTop: number, imgScale: number
      if (w >= h) {
        // 横图：宽度撑满容器，高度居中，上下留白
        imgScale = w          // 容器宽度 1 对应 w 像素
        imgLeft = 0
        imgTop = (1 - h / w) / 2  // 图片顶部在容器中的偏移比例
      } else {
        // 竖图：高度撑满容器，宽度居中，左右留白
        imgScale = h          // 容器高度 1 对应 h 像素
        imgLeft = (1 - w / h) / 2  // 图片左侧在容器中的偏移比例
        imgTop = 0
      }
      // 将容器坐标转为图片像素坐标
      const sx = (rx - imgLeft) * imgScale
      const sy = (ry - imgTop) * imgScale
      let sLen = rSize * imgScale
      // 确保不越界
      sLen = Math.min(sLen, w - Math.max(sx, 0), h - Math.max(sy, 0))
      const clampedSx = Math.max(sx, 0)
      const clampedSy = Math.max(sy, 0)
      const out = Math.min(Math.round(sLen), 512)
      const canvas = document.createElement('canvas')
      canvas.width = out
      canvas.height = out
      const ctx = canvas.getContext('2d')!
      ctx.drawImage(img, clampedSx, clampedSy, sLen, sLen, 0, 0, out, out)
      canvas.toBlob(
        (b) => b ? resolve(b) : reject(new Error('toBlob failed')),
        'image/jpeg',
        0.92,
      )
      URL.revokeObjectURL(img.src)
    }
    img.onerror = () => { URL.revokeObjectURL(img.src); reject(new Error('load failed')) }
    img.src = URL.createObjectURL(file)
  })
}

function uploadAvatar(): void {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = () => {
    const file = input.files?.[0]
    if (!file) return
    if (file.size > 5 * 1024 * 1024) {
      toast.push('头像图片不能超过 5MB', 'warning')
      return
    }
    openCropper(file)
  }
  input.click()
}

function resetToTextAvatar(): void {
  avatar.value = createTextAvatar(nickname.value || props.user.nickname)
}

function saveProfile(): void {
  const cleanNickname = nickname.value.trim()
  const color = currentColor.value
  const normalizedAvatar = isTextAvatarValue(avatar.value)
    ? createTextAvatar(cleanNickname, color || DEFAULT_TEXT_AVATAR_COLOR)
    : avatar.value
  emit('save', { nickname: cleanNickname, avatar: normalizedAvatar })
}
</script>

<template>
  <div v-if="open" class="modal-backdrop detail-backdrop apple-modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="profile-sheet detail-panel apple-modal-surface" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <header class="profile-header">
        <div>
          <p>PROFILE</p>
          <h2 id="profile-title">修改资料</h2>
          <span>@{{ user.username }}</span>
        </div>
        <button class="close-button apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
          <UiIcon name="close" :size="17" />
        </button>
      </header>

      <div class="profile-body">
        <div class="avatar-section">
          <div class="avatar-preview">
            <UserAvatar :name="nickname || user.nickname" :avatar="previewAvatar" :size="88" online />
            <button class="avatar-upload-btn" type="button" :disabled="uploadingAvatar" @click="uploadAvatar">
              <UiIcon name="edit" :size="14" />
            </button>
          </div>
          <button v-if="isImageAvatar" class="reset-avatar" type="button" @click="resetToTextAvatar">使用文字头像</button>
        </div>

        <section v-if="!isImageAvatar" class="avatar-customizer" aria-labelledby="avatar-style-title">
          <div class="section-heading">
            <strong id="avatar-style-title">头像样式</strong>
            <span>选择字符和底色</span>
          </div>
          <div class="text-avatar-row">
            <button
              type="button"
              class="text-avatar-choice"
              :class="{ selected: isTextAvatar }"
              aria-label="使用昵称首字符作为头像"
              :aria-pressed="isTextAvatar"
              @click="selectTextAvatar"
            ><UserAvatar :name="nickname || user.nickname" :avatar="textAvatarPreview" :size="38" /></button>
          </div>

          <div class="color-section">
            <span class="section-label">底色</span>
            <div class="color-row">
              <button
                v-for="color in colorPresets"
                :key="color"
                type="button"
                class="color-swatch"
                :class="{ selected: currentColor === color }"
                :style="{ background: color }"
                :aria-label="`选择颜色 ${color}`"
                @click="selectTextColor(color)"
              />
            </div>
          </div>
        </section>

        <label class="nickname-field"><span>昵称</span><input v-model="nickname" class="field" maxlength="16" /></label>

        <button
          class="primary-button"
          type="button"
          :disabled="saving || uploadingAvatar || !nickname.trim()"
          @click="saveProfile"
        >{{ saving ? '正在保存…' : '保存资料' }}</button>
      </div>
    </section>

    <!-- 裁切器弹窗 -->
    <div v-if="cropperVisible" class="cropper-backdrop apple-modal-backdrop" role="presentation" @click.self="closeCropper">
      <div class="cropper-dialog apple-modal-surface">
        <h3>裁切头像</h3>
        <div ref="cropContainerRef" class="cropper-area">
          <img ref="cropImgRef" :src="cropPreviewUrl" class="cropper-img" draggable="false" @load="onCropImgLoad" />
          <!-- 四周暗色遮罩 -->
          <div class="crop-mask crop-mask--top" :style="{ height: `${cropY * 100}%` }" />
          <div class="crop-mask crop-mask--bottom" :style="{ top: `${(cropY + cropSize) * 100}%`, height: `${(1 - cropY - cropSize) * 100}%` }" />
          <div class="crop-mask crop-mask--left" :style="{ top: `${cropY * 100}%`, height: `${cropSize * 100}%`, width: `${cropX * 100}%` }" />
          <div class="crop-mask crop-mask--right" :style="{ top: `${cropY * 100}%`, height: `${cropSize * 100}%`, left: `${(cropX + cropSize) * 100}%`, width: `${(1 - cropX - cropSize) * 100}%` }" />
          <!-- 裁切框 -->
          <div
            class="crop-box"
            :style="{ left: `${cropX * 100}%`, top: `${cropY * 100}%`, width: `${cropSize * 100}%`, height: `${cropSize * 100}%` }"
            @pointerdown="onCropPointerDown"
          >
            <span class="crop-corner crop-corner--tl" />
            <span class="crop-corner crop-corner--tr" />
            <span class="crop-corner crop-corner--bl" />
            <span class="crop-corner crop-corner--br" @pointerdown="onResizePointerDown" />
          </div>
        </div>
        <div class="cropper-actions">
          <button type="button" class="cropper-cancel" @click="closeCropper">取消</button>
          <button type="button" class="cropper-confirm" :disabled="uploadingAvatar" @click="confirmCrop">
            {{ uploadingAvatar ? '上传中…' : '确认裁切' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 100;
  inset: 0;
  display: grid;
  padding: var(--space-5);
  place-items: center;
  overflow: hidden;
}

.profile-sheet {
  position: relative;
  display: grid;
  width: min(100%, 430px);
  max-height: calc(100dvh - 40px);
  padding: 0;
  grid-template-rows: auto minmax(0, 1fr);
  border-radius: var(--radius-sheet);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow: hidden;
}
.profile-header {
  display: flex;
  padding: 20px 22px 16px;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  border-bottom: 1px solid var(--separator);
  background: var(--surface-glass);
}
.profile-header > div { display: grid; gap: 3px; }
.profile-header p { margin: 0; color: var(--accent-text); font-size: var(--font-micro); font-weight: 750; letter-spacing: .12em; }
.profile-header h2 { margin: 0; font-size: var(--font-title); letter-spacing: -.03em; }
.profile-header span { color: var(--ink-faint); font-size: var(--font-micro); }
.profile-body {
  display: grid;
  min-height: 0;
  padding: 20px 24px 24px;
  justify-items: center;
  align-content: start;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.close-button {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
}
.close-button:hover { background: var(--button-hover); }
.close-button .ui-icon { width: 17px; }

.avatar-section {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
}
.avatar-preview {
  position: relative;
  display: inline-block;
}
.avatar-upload-btn {
  position: absolute;
  right: -4px;
  bottom: -4px;
  display: grid;
  width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
  border: 2px solid #fff;
  border-radius: 50%;
  color: #fff;
  background: var(--blue);
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 122, 255, 0.3);
  transition: transform 150ms ease;
}
.avatar-upload-btn:hover { transform: scale(1.1); }
.avatar-upload-btn:disabled { opacity: 0.5; cursor: wait; }
.avatar-upload-btn .ui-icon { width: 14px; }

.reset-avatar {
  padding: 0;
  border: 0;
  color: var(--accent-text);
  font-size: var(--font-micro);
  font-weight: 600;
  background: none;
  cursor: pointer;
}
.reset-avatar:hover { text-decoration: underline; }
.avatar-customizer {
  width: 100%;
  padding: 13px;
  margin-top: 18px;
  border: 1px solid var(--separator);
  border-radius: 16px;
  background: var(--surface-tint);
}
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
.section-heading strong { font-size: var(--font-caption); }
.section-heading span { color: var(--ink-faint); font-size: var(--font-caption); }

.text-avatar-row { display: flex; width: 100%; margin: 11px 0 4px; }
.text-avatar-choice { display: grid; width: 44px; height: 44px; padding: 2px; place-items: center; border: 2px solid transparent; border-radius: 50%; background: transparent; cursor: pointer; transition: border-color 150ms ease, transform 150ms ease; }
.text-avatar-choice.selected { border-color: rgba(0, 122, 255, 0.48); }
.text-avatar-choice:hover { transform: scale(1.06); }

.color-section {
  width: 100%;
  margin: 10px 0 0;
}
.section-label {
  display: block;
  margin-bottom: 8px;
  color: var(--ink-soft);
  font-size: var(--font-micro);
  font-weight: 600;
}
.color-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(44px, 1fr));
  gap: 8px;
}
.color-swatch {
  min-width: 44px;
  min-height: 44px;
  aspect-ratio: 1;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 150ms ease, box-shadow 150ms ease;
}
.color-swatch:hover { transform: scale(1.15); }
.color-swatch.selected {
  border-color: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 2px currentColor, 0 2px 8px rgba(0, 0, 0, 0.15);
  transform: scale(1.1);
}

.nickname-field { display: grid; width: 100%; margin-top: 16px; gap: var(--space-2); }
.nickname-field > span { color: var(--ink-soft); font-size: var(--font-caption); font-weight: 600; }
.profile-sheet .primary-button { width: 100%; margin-top: 14px; }


@media (max-width: 760px) {
  .modal-backdrop { padding: 0; place-items: stretch; }
  .profile-sheet {
    width: 100%;
    height: 100dvh;
    min-height: 0;
    max-height: 100dvh;
    border-radius: 0;
    box-shadow: none;
  }
  .profile-header {
    padding: max(14px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) 13px max(16px, env(safe-area-inset-left));
    align-items: center;
  }
  .profile-header h2 { font-size: var(--font-title); }
  .close-button { width: 44px; height: 44px; }
  .profile-body {
    padding: 18px max(16px, env(safe-area-inset-right)) max(24px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
    scrollbar-gutter: auto;
  }
  .avatar-customizer { margin-top: 16px; }
}

/* ===== 裁切器 ===== */
.cropper-backdrop {
  position: fixed;
  z-index: 200;
  inset: 0;
  display: grid;
  padding: var(--space-5);
  place-items: center;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
  isolation: isolate;
}
.cropper-dialog {
  display: grid;
  width: min(100%, 420px);
  padding: 22px;
  gap: var(--space-4);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 24px 60px var(--shadow-color);
}
.cropper-dialog h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  text-align: center;
}
.cropper-area {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: var(--radius-control);
  background: #1a1a1a;
  user-select: none;
  touch-action: none;
}
.cropper-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}
.crop-mask {
  position: absolute;
  background: rgba(0, 0, 0, 0.5);
  pointer-events: none;
}
.crop-mask--top { top: 0; left: 0; width: 100%; }
.crop-mask--bottom { left: 0; width: 100%; }
.crop-mask--left { left: 0; }
.crop-box {
  position: absolute;
  border: 2px solid #fff;
  border-radius: 4px;
  cursor: move;
  box-shadow: 0 0 0 9999px transparent;
}
.crop-corner {
  position: absolute;
  width: 16px;
  height: 16px;
  border-color: #fff;
  border-style: solid;
  border-width: 0;
}
.crop-corner--tl { top: -2px; left: -2px; border-top-width: 3px; border-left-width: 3px; border-top-left-radius: 4px; }
.crop-corner--tr { top: -2px; right: -2px; border-top-width: 3px; border-right-width: 3px; border-top-right-radius: 4px; }
.crop-corner--bl { bottom: -2px; left: -2px; border-bottom-width: 3px; border-left-width: 3px; border-bottom-left-radius: 4px; }
.crop-corner--br { bottom: -2px; right: -2px; border-bottom-width: 3px; border-right-width: 3px; border-bottom-right-radius: 4px; cursor: nwse-resize; }
.cropper-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
.cropper-actions button {
  min-height: 38px;
  padding: 0 20px;
  border: 0;
  border-radius: var(--radius-control);
  font-size: var(--font-body-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 150ms ease;
}
.cropper-cancel {
  color: var(--ink);
  background: var(--fill, #f2f2f7);
}
.cropper-cancel:hover { background: var(--button-hover); }
.cropper-confirm {
  color: #fff;
  background: var(--blue, #007AFF);
}
.cropper-confirm:hover { background: color-mix(in srgb, var(--blue) 88%, #000); }
.cropper-confirm:disabled { opacity: 0.5; cursor: wait; }

@media (max-width: 760px) {
  .cropper-backdrop {
    padding: max(12px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  }
  .cropper-dialog {
    max-height: 100%;
    padding: 18px;
    overflow-y: auto;
  }
  .cropper-area {
    width: min(100%, calc(100dvh - 190px));
    justify-self: center;
  }
  .cropper-actions button { min-height: 44px; flex: 1; }
}
</style>
