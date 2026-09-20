<script setup lang="ts">
import {
  nextTick,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
} from 'vue'
import {
  getDocument,
  GlobalWorkerOptions,
  type PDFDocumentLoadingTask,
  type PDFDocumentProxy,
  type RenderTask,
} from 'pdfjs-dist'
import workerSrc from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { nodeFetch } from '../../../platform/nativeTransport'
import { nativeBridge } from "../../../platform/nativeBridge"
import { currentNodeOrigin } from "../../../platform/nodeContext"

GlobalWorkerOptions.workerSrc = workerSrc

const props = defineProps<{
  url: string
}>()

const container = shallowRef<HTMLElement | null>(null)
const canvas = shallowRef<HTMLCanvasElement | null>(null)

const loading = ref(false)
const errorMessage = ref('')
const pageNumber = ref(1)
const pageCount = ref(0)

let pdfLoadingTask: PDFDocumentLoadingTask | null = null
let pdfDocument: PDFDocumentProxy | null = null
let renderTask: RenderTask | null = null
let loadSequence = 0

watch(
    () => props.url,
    () => {
      void loadPdf()
    },
    { immediate: true },
)

async function loadPdf(): Promise<void> {
  const sequence = ++loadSequence

  loading.value = true
  errorMessage.value = ''
  pageNumber.value = 1
  pageCount.value = 0

  try {
    await destroyDocument()

    if (!props.url) {
      throw new Error('没有可用的 PDF 地址')
    }

    console.log('MeshX PDF 开始加载', {
      runtime: nativeBridge.runtime(),
      workerSrc,
      url: (() => {
        try {
          const parsed = new URL(
              props.url,
              window.location.href,
          )

          return {
            protocol: parsed.protocol,
            origin: parsed.origin,
            pathname: parsed.pathname,
          }
        } catch {
          return props.url
        }
      })(),
    })

    const response = await fetchPdfSource(props.url)

    console.log('MeshX PDF 响应', {
      status: response.status,
      contentType:
          response.headers.get('content-type'),
      contentLength:
          response.headers.get('content-length'),
    })

    if (!response.ok) {
      throw new Error(
          `PDF 请求失败：HTTP ${response.status}`,
      )
    }

    const buffer = await response.arrayBuffer()

    if (buffer.byteLength === 0) {
      throw new Error('服务器返回了空 PDF')
    }

    const bytes = new Uint8Array(buffer)

    const headerText = new TextDecoder().decode(
        bytes.subarray(
            0,
            Math.min(bytes.length, 1024),
        ),
    )

    if (!headerText.includes('%PDF-')) {
      const contentType =
          response.headers.get('content-type')
          || '未知类型'

      throw new Error(
          `服务器返回的不是 PDF，Content-Type：${contentType}`,
      )
    }

    const loadingTask = getDocument({
      data: bytes,
    })

    pdfLoadingTask = loadingTask

    const document = await loadingTask.promise

    if (sequence !== loadSequence) {
      await loadingTask.destroy()

      if (pdfLoadingTask === loadingTask) {
        pdfLoadingTask = null
      }

      return
    }

    pdfDocument = document
    pageCount.value = document.numPages

    await nextTick()
    await renderCurrentPage()
  } catch (error) {
    if (sequence !== loadSequence) return

    console.error(
        'MeshX PDF 预览失败：',
        error,
    )

    errorMessage.value =
        resolveErrorMessage(error)
  } finally {
    if (sequence === loadSequence) {
      loading.value = false
    }
  }
}
async function fetchPdfSource(url: string): Promise<Response> {
  if (url.startsWith('blob:') || url.startsWith('data:')) return fetch(url)
  if (nativeBridge.runtime() === 'tauri') {
    const parsed = new URL(url, currentNodeOrigin())
    return nodeFetch(`${parsed.pathname}${parsed.search}`,)
  }
  return nodeFetch(url)
}

async function renderCurrentPage(): Promise<void> {
  if (!pdfDocument || !canvas.value) return

  renderTask?.cancel()
  renderTask = null

  const page = await pdfDocument.getPage(pageNumber.value)
  const originalViewport = page.getViewport({ scale: 1 })

  const containerWidth =
      container.value?.clientWidth
      ?? originalViewport.width

  /*
   * 减去容器左右内边距。
   * 最大宽度限制可以避免超宽 PDF 在桌面端无限放大。
   */
  const availableWidth = Math.min(
      Math.max(containerWidth - 32, 260),
      1080,
  )

  const scale = availableWidth / originalViewport.width
  const viewport = page.getViewport({ scale })

  /*
   * Retina 屏幕使用设备像素比，提高文字清晰度。
   * 限制到 2，避免超大 PDF 占用过多内存。
   */
  const outputScale = Math.min(
      window.devicePixelRatio || 1,
      2,
  )

  const targetCanvas = canvas.value

  targetCanvas.width =
      Math.floor(viewport.width * outputScale)

  targetCanvas.height =
      Math.floor(viewport.height * outputScale)

  targetCanvas.style.width =
      `${Math.floor(viewport.width)}px`

  // max-width can shrink the canvas after a window resize. Let the intrinsic
  // bitmap ratio determine its height as well, so document pages never stretch.
  targetCanvas.style.height = 'auto'


  renderTask = page.render({
    canvas: targetCanvas,
    viewport,
    transform: outputScale === 1
        ? undefined
        : [
          outputScale,
          0,
          0,
          outputScale,
          0,
          0,
        ],
  })

  try {
    await renderTask.promise
  } catch (error) {
    /*
     * 翻页、关闭窗口时主动取消渲染不应提示错误。
     */
    if (
        error instanceof Error
        && error.name === 'RenderingCancelledException'
    ) {
      return
    }

    throw error
  } finally {
    renderTask = null
  }
}

async function previousPage(): Promise<void> {
  if (pageNumber.value <= 1) return

  pageNumber.value -= 1
  loading.value = true

  try {
    await renderCurrentPage()
  } finally {
    loading.value = false
  }
}

async function nextPage(): Promise<void> {
  if (pageNumber.value >= pageCount.value) return

  pageNumber.value += 1
  loading.value = true

  try {
    await renderCurrentPage()
  } finally {
    loading.value = false
  }
}

function resolveErrorMessage(error: unknown): string {
  if (!(error instanceof Error)) {
    return `PDF 暂时无法预览: ${String(error)}`
  }

  if (/password/i.test(error.message)) {
    return '该 PDF 已加密，暂不支持在线预览'
  }

  if (/invalid pdf/i.test(error.message)) {
    return '文件内容不是有效的 PDF'
  }

  if (/离开了所选节点/.test(error.message)) {
    return 'PDF 地址与当前节点不一致'
  }

  if (/worker|fake worker/i.test(error.message)) {
    return `PDF Worker 加载失败: ${error.message}`
  }

  return `PDF 暂时无法预览: ${error.message}`
}

async function destroyDocument(): Promise<void> {
  if (renderTask) {
    renderTask.cancel()
    renderTask = null
  }

  pdfDocument = null

  if (!pdfLoadingTask) return

  const loadingTask = pdfLoadingTask
  pdfLoadingTask = null

  try {
    await loadingTask.destroy()
  } catch (error) {
    console.warn(
        'MeshX PDF 加载任务清理失败：',
        error,
    )
  }
}

onBeforeUnmount(() => {
  loadSequence += 1
  void destroyDocument()
})
</script>

<template>
  <section class="pdf-preview">
    <div
        ref="container"
        class="pdf-page-container"
    >
      <div
          v-if="errorMessage"
          class="pdf-state pdf-error"
      >
        {{ errorMessage }}
      </div>

      <template v-else>
        <canvas ref="canvas" />

        <div
            v-if="loading"
            class="pdf-loading"
        >
          正在载入 PDF…
        </div>
      </template>
    </div>

    <footer
        v-if="!errorMessage && pageCount > 0"
        class="pdf-toolbar"
    >
      <button
          type="button"
          :disabled="loading || pageNumber <= 1"
          @click="previousPage"
      >
        上一页
      </button>

      <span>
        {{ pageNumber }} / {{ pageCount }}
      </span>

      <button
          type="button"
          :disabled="loading || pageNumber >= pageCount"
          @click="nextPage"
      >
        下一页
      </button>
    </footer>
  </section>
</template>

<style scoped>
.pdf-preview {
  display: grid;
  width: 100%;
  height: 100%;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  overflow: hidden;
}

.pdf-page-container {
  position: relative;
  min-width: 0;
  min-height: 0;
  padding: var(--space-4);
  overflow: auto;
  text-align: center;
  background: var(--preview-canvas);
}

.pdf-page-container canvas {
  display: inline-block;
  max-width: 100%;
  background: var(--document-paper);
  box-shadow: 0 8px 28px rgba(0, 0, 0, .18);
}

.pdf-loading {
  position: absolute;
  top: 16px;
  left: 50%;
  padding: 7px 12px;
  border-radius: var(--radius-pill);
  color: white;
  font-size: var(--font-caption);
  background: rgba(22, 28, 36, .72);
  transform: translateX(-50%);
  backdrop-filter: blur(10px);
}

.pdf-state {
  display: grid;
  min-height: min(320px, 50dvh);
  place-items: center;
  color: var(--ink-soft);
  font-size: var(--font-body-sm);
}

.pdf-error {
  color: var(--danger);
}

.pdf-toolbar {
  display: flex;
  min-height: 54px;
  padding:
      8px
      max(14px, env(safe-area-inset-right, 0px))
      calc(8px + env(safe-area-inset-bottom, 0px))
      max(14px, env(safe-area-inset-left, 0px));
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  border-top: 1px solid var(--separator);
  background: var(--surface);
}

.pdf-toolbar button {
  min-height: var(--control-height);
  padding: 0 14px;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  color: var(--ink);
  font: inherit;
  font-size: var(--font-caption);
  background: var(--surface);
  cursor: pointer;
}

.pdf-toolbar button:disabled {
  cursor: default;
  opacity: .42;
}

.pdf-toolbar span {
  min-width: 64px;
  color: var(--ink-soft);
  font-size: var(--font-caption);
  text-align: center;
}
</style>
