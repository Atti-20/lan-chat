import { currentNodeKey, resourceUrl } from '../platform/nodeContext'
import { api } from './api'
import {
  cacheAvatarImage,
  deleteCachedAvatarImage,
  loadCachedAvatarImage,
} from './localChatDb'

interface MemoryAvatarImage {
  url: string
  blobUrl: boolean
}

const memoryImages = new Map<string, MemoryAvatarImage>()
const inFlightImages = new Map<string, Promise<string>>()

function cacheKey(avatar: string): string {
  return `${currentNodeKey()}::${avatar}`
}

function isProtectedAvatar(avatar: string): boolean {
  return avatar.startsWith('/api/v1/file/content') || avatar.startsWith('/api/v1/file/preview')
}

function isUsableImage(response: Response): boolean {
  const contentType = response.headers.get('Content-Type') || ''
  return response.ok && contentType.toLowerCase().startsWith('image/')
}

function rememberBlob(cacheId: string, blob: Blob): string {
  const existing = memoryImages.get(cacheId)
  if (existing?.blobUrl) URL.revokeObjectURL(existing.url)
  const url = URL.createObjectURL(blob)
  memoryImages.set(cacheId, { url, blobUrl: true })
  return url
}

async function fetchAvatarBlob(avatar: string): Promise<Blob> {
  const url = isProtectedAvatar(avatar)
    ? await api.files.temporaryUrl(avatar)
    : resourceUrl(avatar)
  const response = await fetch(url)
  if (!isUsableImage(response)) {
    throw new Error(`头像请求失败：HTTP ${response.status}`)
  }
  const blob = await response.blob()
  if (blob.size === 0) throw new Error('头像内容为空')
  return blob
}

export async function resolveCachedAvatarImage(avatar: string): Promise<string> {
  const id = cacheKey(avatar)
  const memory = memoryImages.get(id)
  if (memory) return memory.url

  const active = inFlightImages.get(id)
  if (active) return active

  const request = (async () => {
    const local = await loadCachedAvatarImage(id).catch(() => null)
    if (local) return rememberBlob(id, local.blob)

    try {
      const blob = await fetchAvatarBlob(avatar)
      const url = rememberBlob(id, blob)
      void cacheAvatarImage(id, blob).catch(() => undefined)
      return url
    } catch (cause) {
      // An externally hosted legacy avatar can allow image embedding while
      // disallowing fetch with CORS. Keep that legacy display path available,
      // but never persist an unverified response.
      if (!isProtectedAvatar(avatar)) return resourceUrl(avatar)
      throw cause
    }
  })()
  inFlightImages.set(id, request)

  try {
    return await request
  } finally {
    inFlightImages.delete(id)
  }
}

export async function invalidateCachedAvatarImage(avatar: string): Promise<void> {
  const id = cacheKey(avatar)
  const memory = memoryImages.get(id)
  if (memory?.blobUrl) URL.revokeObjectURL(memory.url)
  memoryImages.delete(id)
  await deleteCachedAvatarImage(id).catch(() => undefined)
}
