/** Keep ordinary files intact; prepare oversized camera photos before hashing. */
export async function prepareUploadImage(file: File, signal?: AbortSignal): Promise<File> {
  signal?.throwIfAborted()
  const heif = /image\/hei[cf]/i.test(file.type) || /\.hei[cf]$/i.test(file.name)
  if (!heif && !/^image\/(jpeg|png)$/i.test(file.type) && !/\.(jpe?g|png)$/i.test(file.name)) return file
  const url = URL.createObjectURL(file)
  const image = new Image()
  let bitmap: ImageBitmap | undefined
  let canvas: HTMLCanvasElement | undefined
  try {
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => signal?.removeEventListener('abort', abort)
      const abort = () => { cleanup(); image.src = ''; reject(signal?.reason ?? new DOMException('Aborted', 'AbortError')) }
      image.onload = () => { cleanup(); resolve() }
      image.onerror = () => { cleanup(); reject(new Error(heif ? '此设备无法转换 HEIC 图片，请选择 JPEG 或 PNG' : '无法读取图片')) }
      signal?.addEventListener('abort', abort, { once: true })
      image.src = url
    })
    signal?.throwIfAborted()
    if (!heif && image.naturalWidth * image.naturalHeight <= 40_000_000) return file
    const ratio = Math.min(1, 4096 / Math.max(image.naturalWidth, image.naturalHeight))
    const width = Math.max(1, Math.floor(image.naturalWidth * ratio))
    const height = Math.max(1, Math.floor(image.naturalHeight * ratio))
    bitmap = await createImageBitmap(file, { resizeWidth: width, resizeHeight: height, imageOrientation: 'from-image' })
    signal?.throwIfAborted()
    canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法处理图片')
    context.drawImage(bitmap, 0, 0, width, height)
    const png = file.type === 'image/png' || /\.png$/i.test(file.name)
    const type = png ? 'image/png' : 'image/jpeg'
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas!.toBlob(value => value ? resolve(value) : reject(new Error('无法处理图片')), type, 0.9)
    })
    signal?.throwIfAborted()
    if (blob.size > 25 * 1024 * 1024) throw new Error('处理后的图片仍超过 25 MB，请选择较小的图片')
    return new File([blob], `${file.name.replace(/\.[^.]+$/, '').slice(0, 100)}-meshx.${png ? 'png' : 'jpg'}`, { type, lastModified: file.lastModified })
  } finally {
    image.onload = null; image.onerror = null; image.src = ''
    URL.revokeObjectURL(url)
    bitmap?.close()
    if (canvas) { canvas.width = 0; canvas.height = 0 }
  }
}
