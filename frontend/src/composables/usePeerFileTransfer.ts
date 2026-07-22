import { computed, onBeforeUnmount, readonly, shallowRef } from 'vue'
import {
  deleteDirectFile,
  loadDirectFile,
  moveDirectFile,
  saveDirectFile,
} from '../services/localChatDb'
import { subscribeRealtimeEvents } from '../services/realtimeEvents'
import type { DirectFileRecord, FileAttachmentData, WsEnvelope } from '../types'
import { createClientMessageId } from '../utils/id'
import { sha256Blob } from '../utils/sha256'

const CHUNK_SIZE = 64 * 1024
const BUFFER_HIGH_WATER = 4 * 1024 * 1024
const NEGOTIATION_TIMEOUT_MS = 2_000
const TRANSFER_TIMEOUT_MS = 10 * 60_000
const ICE_GATHERING_TIMEOUT_MS = 2_500
const MAX_DIRECT_FILE_SIZE = 100 * 1024 * 1024

type TransferPhase = 'IDLE' | 'HASHING' | 'NEGOTIATING' | 'TRANSFERRING' | 'VERIFYING'

interface UsePeerFileTransferOptions {
  sendEvent: (
    event: string,
    payload?: Record<string, unknown>,
    metadata?: Partial<Pick<WsEnvelope, 'requestId' | 'conversationId'>>,
  ) => boolean
}

interface OutgoingTransfer {
  transferId: string
  peer: RTCPeerConnection
  channel: RTCDataChannel
  file: File
  fileHash: string
  conversationId: string
  toUserId: number
  timer: number
  resolve: (attachment: FileAttachmentData) => void
  reject: (cause: Error) => void
  sending: boolean
  serverAssigned: boolean
}

interface IncomingTransfer {
  transferId: string
  peer: RTCPeerConnection
  conversationId: string
  fromUserId: number
  name: string
  mime: string
  size: number
  fileHash: string
  chunks: ArrayBuffer[]
  receivedBytes: number
  completed: boolean
}

export function usePeerFileTransfer(options: UsePeerFileTransferOptions) {
  const phase = shallowRef<TransferPhase>('IDLE')
  const progress = shallowRef(0)
  const activePath = shallowRef<'PEER_TO_PEER' | null>(null)
  const lastFailedTransferId = shallowRef<string | null>(null)
  const supported = computed(() => typeof RTCPeerConnection !== 'undefined')

  const outgoing = new Map<string, OutgoingTransfer>()
  const incoming = new Map<string, IncomingTransfer>()
  let sendInProgress = false
  let directAbortController: AbortController | null = null
  const unsubscribe = subscribeRealtimeEvents(handleRealtimeEvent)

  onBeforeUnmount(() => {
    unsubscribe()
    directAbortController?.abort()
    outgoing.forEach((transfer) => failOutgoing(transfer, new Error('页面已关闭')))
    incoming.forEach((transfer) => transfer.peer.close())
    incoming.clear()
  })

  async function sendDirect(
    file: File,
    conversationId: string,
    toUserId: number,
  ): Promise<FileAttachmentData> {
    if (!supported.value) throw new Error('当前浏览器不支持 WebRTC 文件直传')
    if (!conversationId.startsWith('private:') || toUserId <= 0) {
      throw new Error('当前会话不适合设备直传')
    }
    if (file.size <= 0 || file.size > MAX_DIRECT_FILE_SIZE) {
      throw new Error('文件大小不适合设备直传')
    }
    if (sendInProgress) throw new Error('已有文件正在设备直传')

    sendInProgress = true
    const controller = new AbortController()
    directAbortController = controller
    phase.value = 'HASHING'
    progress.value = 0
    activePath.value = 'PEER_TO_PEER'
    lastFailedTransferId.value = null
    const clientTransferId = createClientMessageId()
    const transferRef: { current: OutgoingTransfer | null } = { current: null }
    let peer: RTCPeerConnection | null = null
    let channel: RTCDataChannel | null = null
    let localFileSaved = false
    let completed = false
    try {
      const fileHash = await sha256Blob(file, controller.signal)
      throwIfDirectAborted(controller.signal)
      const mime = safeMime(file.type)
      await saveDirectFile({
        transferId: clientTransferId,
        name: file.name,
        mime,
        size: file.size,
        fileHash,
        blob: file,
        savedAt: new Date().toISOString(),
      })
      localFileSaved = true
      throwIfDirectAborted(controller.signal)

      const createdPeer = createPeer()
      peer = createdPeer
      const createdChannel = createdPeer.createDataChannel('lanchat-file', { ordered: true })
      channel = createdChannel
      createdChannel.binaryType = 'arraybuffer'
      createdChannel.bufferedAmountLowThreshold = BUFFER_HIGH_WATER / 2

      const result = new Promise<FileAttachmentData>((resolve, reject) => {
        const createdTransfer: OutgoingTransfer = {
          transferId: clientTransferId,
          peer: createdPeer,
          channel: createdChannel,
          file,
          fileHash,
          conversationId,
          toUserId,
          resolve,
          reject,
          sending: false,
          serverAssigned: false,
          timer: 0,
        }
        createdTransfer.timer = window.setTimeout(() => {
          controller.abort()
          failOutgoing(createdTransfer, new Error('设备直传协商超时'))
        }, NEGOTIATION_TIMEOUT_MS)
        transferRef.current = createdTransfer
        outgoing.set(clientTransferId, createdTransfer)
        createdChannel.onopen = () => {
          window.clearTimeout(createdTransfer.timer)
          createdTransfer.timer = window.setTimeout(() => {
            controller.abort()
            failOutgoing(createdTransfer, new Error('设备直传超时'))
          }, TRANSFER_TIMEOUT_MS)
          void streamOutgoing(createdTransfer)
        }
        createdChannel.onerror = () => failOutgoing(createdTransfer, new Error('设备直传通道异常'))
        createdChannel.onclose = () => {
          if (outgoing.has(createdTransfer.transferId) && !createdTransfer.sending) {
            failOutgoing(createdTransfer, new Error('设备直传通道已关闭'))
          }
        }
      })

      phase.value = 'NEGOTIATING'
      try {
        const offer = await createdPeer.createOffer()
        await createdPeer.setLocalDescription(offer)
        await waitForIceGathering(createdPeer, controller.signal)
        throwIfDirectAborted(controller.signal)
        const sent = options.sendEvent('FILE_TRANSFER_OFFER', {
          transferId: clientTransferId,
          toUserId,
          name: file.name,
          size: file.size,
          mime,
          fileHash,
          sdp: createdPeer.localDescription?.sdp || offer.sdp,
        }, { requestId: requestId(), conversationId })
        if (!sent) throw new Error('实时连接不可用')
      } catch (cause) {
        const error = asError(cause, '设备直传失败')
        const currentTransfer = transferRef.current
        if (currentTransfer && outgoing.has(currentTransfer.transferId)) {
          failOutgoing(currentTransfer, error)
        }
        // Consume the promise rejection because setup failed before the normal
        // result-await path below was reached.
        await result.catch(() => undefined)
        throw error
      }

      const attachment = await result
      completed = true
      return attachment
    } finally {
      if (directAbortController === controller) directAbortController = null
      if (!completed) {
        channel?.close()
        peer?.close()
      }
      if (!completed && localFileSaved) {
        const failedIds = new Set([clientTransferId])
        const currentTransferId = transferRef.current?.transferId
        if (currentTransferId) failedIds.add(currentTransferId)
        await Promise.all([...failedIds].map((transferId) =>
          deleteDirectFile(transferId).catch(() => undefined)))
      }
      if (!completed && outgoing.size === 0) {
        phase.value = 'IDLE'
        progress.value = 0
        activePath.value = null
      }
      sendInProgress = false
    }
  }

  async function handleRealtimeEvent(envelope: WsEnvelope): Promise<void> {
    switch (envelope.event) {
      case 'FILE_TRANSFER_OFFER':
        await receiveOffer(envelope)
        return
      case 'FILE_TRANSFER_ANSWER':
        await receiveAnswer(envelope)
        return
      case 'FILE_TRANSFER_READY':
        await remapOutgoingTransfer(envelope)
        return
      case 'FILE_TRANSFER_COMPLETE':
        completeOutgoing(String(envelope.payload.transferId || ''))
        return
      case 'FILE_TRANSFER_REJECTED':
      case 'FILE_TRANSFER_FAILED':
      case 'FILE_TRANSFER_CANCELED': {
        const transferId = String(envelope.payload.transferId || '')
        const transfer = outgoing.get(transferId)
        if (transfer) failOutgoing(transfer, new Error(String(
          envelope.payload.message || envelope.payload.reason || '对端无法接收直传文件',
        )))
        const receiver = incoming.get(transferId)
        if (receiver) cleanupIncoming(receiver)
      }
    }
  }

  async function receiveOffer(envelope: WsEnvelope): Promise<void> {
    if (!supported.value) return
    const payload = envelope.payload
    const transferId = String(payload.transferId || '')
    const conversationId = envelope.conversationId || String(payload.conversationId || '')
    const fromUserId = Number(payload.fromUserId)
    const name = String(payload.name || '').slice(0, 255)
    const mime = safeMime(String(payload.mime || ''))
    const size = Number(payload.size)
    const fileHash = String(payload.fileHash || '').toLowerCase()
    const sdp = String(payload.sdp || '')
    if (!/^[a-f0-9]{32}$/.test(transferId)
      || !conversationId.startsWith('private:')
      || !Number.isSafeInteger(fromUserId) || fromUserId <= 0
      || !name || !Number.isSafeInteger(size) || size <= 0 || size > MAX_DIRECT_FILE_SIZE
      || !/^[a-f0-9]{64}$/.test(fileHash) || !sdp) return
    if (incoming.has(transferId)) return

    const peer = createPeer()
    const transfer: IncomingTransfer = {
      transferId,
      peer,
      conversationId,
      fromUserId,
      name,
      mime,
      size,
      fileHash,
      chunks: [],
      receivedBytes: 0,
      completed: false,
    }
    incoming.set(transferId, transfer)
    peer.ondatachannel = (event) => configureIncomingChannel(transfer, event.channel)

    try {
      await peer.setRemoteDescription({ type: 'offer', sdp })
      const answer = await peer.createAnswer()
      await peer.setLocalDescription(answer)
      await waitForIceGathering(peer)
      const sent = options.sendEvent('FILE_TRANSFER_ANSWER', {
        transferId,
        sdp: peer.localDescription?.sdp || answer.sdp,
      }, { requestId: requestId(), conversationId })
      if (!sent) cleanupIncoming(transfer)
    } catch {
      cleanupIncoming(transfer)
      options.sendEvent('FILE_TRANSFER_FAILED', {
        transferId,
        reason: 'ANSWER_FAILED',
      }, { requestId: requestId(), conversationId })
    }
  }

  async function receiveAnswer(envelope: WsEnvelope): Promise<void> {
    const transferId = String(envelope.payload.transferId || '')
    const transfer = outgoing.get(transferId)
    if (!transfer || transfer.peer.remoteDescription) return
    const sdp = String(envelope.payload.sdp || '')
    if (!sdp) {
      failOutgoing(transfer, new Error('对端返回了无效的直传协商信息'))
      return
    }
    try {
      await transfer.peer.setRemoteDescription({ type: 'answer', sdp })
    } catch {
      failOutgoing(transfer, new Error('设备直传协商失败'))
    }
  }

  async function remapOutgoingTransfer(envelope: WsEnvelope): Promise<void> {
    const clientTransferId = String(envelope.payload.clientTransferId || '')
    const serverTransferId = String(envelope.payload.transferId || '')
    if (!/^[a-f0-9]{32}$/.test(serverTransferId) || clientTransferId === serverTransferId) return
    const transfer = outgoing.get(clientTransferId)
    if (!transfer) return
    outgoing.delete(clientTransferId)
    transfer.transferId = serverTransferId
    transfer.serverAssigned = true
    outgoing.set(serverTransferId, transfer)
    try {
      await moveDirectFile(clientTransferId, serverTransferId)
    } catch {
      failOutgoing(transfer, new Error('无法保存直传文件的本地索引'))
    }
  }

  function configureIncomingChannel(transfer: IncomingTransfer, channel: RTCDataChannel): void {
    channel.binaryType = 'arraybuffer'
    channel.onmessage = (event) => {
      if (typeof event.data === 'string') {
        if (event.data === '{"type":"complete"}') void finishIncoming(transfer)
        return
      }
      if (transfer.completed || !(event.data instanceof ArrayBuffer)) return
      transfer.receivedBytes += event.data.byteLength
      if (transfer.receivedBytes > transfer.size) {
        cleanupIncoming(transfer)
        options.sendEvent('FILE_TRANSFER_FAILED', {
          transferId: transfer.transferId,
          reason: 'SIZE_MISMATCH',
        }, { requestId: requestId(), conversationId: transfer.conversationId })
        return
      }
      transfer.chunks.push(event.data)
    }
    channel.onerror = () => cleanupIncoming(transfer)
  }

  async function finishIncoming(transfer: IncomingTransfer): Promise<void> {
    if (transfer.completed) return
    transfer.completed = true
    if (transfer.receivedBytes !== transfer.size) {
      rejectIncoming(transfer, 'SIZE_MISMATCH')
      return
    }
    try {
      const blob = new Blob(transfer.chunks, { type: transfer.mime })
      const actualHash = await sha256Blob(blob)
      if (actualHash !== transfer.fileHash) {
        rejectIncoming(transfer, 'HASH_MISMATCH')
        return
      }
      await saveDirectFile({
        transferId: transfer.transferId,
        name: transfer.name,
        mime: transfer.mime,
        size: transfer.size,
        fileHash: transfer.fileHash,
        blob,
        savedAt: new Date().toISOString(),
      })
      const completionSent = options.sendEvent('FILE_TRANSFER_COMPLETE', {
        transferId: transfer.transferId,
        fileHash: transfer.fileHash,
        fileSize: transfer.receivedBytes,
      }, { requestId: requestId(), conversationId: transfer.conversationId })
      if (!completionSent) {
        await deleteDirectFile(transfer.transferId).catch(() => undefined)
        rejectIncoming(transfer, 'COMPLETE_SIGNAL_FAILED')
        return
      }
      cleanupIncoming(transfer)
    } catch {
      rejectIncoming(transfer, 'LOCAL_STORAGE_FAILED')
    }
  }

  function rejectIncoming(transfer: IncomingTransfer, reason: string): void {
    options.sendEvent('FILE_TRANSFER_FAILED', {
      transferId: transfer.transferId,
      reason,
    }, { requestId: requestId(), conversationId: transfer.conversationId })
    cleanupIncoming(transfer)
  }

  async function streamOutgoing(transfer: OutgoingTransfer): Promise<void> {
    if (transfer.sending || !outgoing.has(transfer.transferId)) return
    transfer.sending = true
    phase.value = 'TRANSFERRING'
    try {
      options.sendEvent('FILE_TRANSFER_STARTED', {
        transferId: transfer.transferId,
      }, { requestId: requestId(), conversationId: transfer.conversationId })
      for (let offset = 0; offset < transfer.file.size; offset += CHUNK_SIZE) {
        await waitForBuffer(transfer.channel)
        const chunk = await transfer.file.slice(offset, offset + CHUNK_SIZE).arrayBuffer()
        if (transfer.channel.readyState !== 'open') throw new Error('设备直传通道已关闭')
        transfer.channel.send(chunk)
        progress.value = Math.min(99, Math.round((offset + chunk.byteLength) / transfer.file.size * 100))
      }
      transfer.channel.send('{"type":"complete"}')
      phase.value = 'VERIFYING'
    } catch (cause) {
      failOutgoing(transfer, asError(cause, '设备直传中断'))
    }
  }

  function completeOutgoing(transferId: string): void {
    const transfer = outgoing.get(transferId)
    if (!transfer) return
    window.clearTimeout(transfer.timer)
    outgoing.delete(transferId)
    progress.value = 100
    phase.value = 'IDLE'
    activePath.value = null
    lastFailedTransferId.value = null
    transfer.channel.close()
    transfer.peer.close()
    transfer.resolve({
      transferId,
      name: transfer.file.name,
      size: transfer.file.size,
      mime: safeMime(transfer.file.type),
      fileHash: transfer.fileHash,
      transferPath: 'PEER_TO_PEER',
    })
  }

  function failOutgoing(transfer: OutgoingTransfer, cause: Error): void {
    if (!outgoing.has(transfer.transferId)) return
    window.clearTimeout(transfer.timer)
    outgoing.delete(transfer.transferId)
    lastFailedTransferId.value = transfer.serverAssigned ? transfer.transferId : null
    transfer.channel.close()
    transfer.peer.close()
    phase.value = 'IDLE'
    progress.value = 0
    activePath.value = null
    options.sendEvent('FILE_TRANSFER_FALLBACK', {
      transferId: transfer.transferId,
      reason: cause.message.slice(0, 160),
    }, { requestId: requestId(), conversationId: transfer.conversationId })
    transfer.reject(cause)
  }

  function cleanupIncoming(transfer: IncomingTransfer): void {
    if (!incoming.has(transfer.transferId)) return
    incoming.delete(transfer.transferId)
    transfer.chunks.length = 0
    transfer.peer.close()
  }

  return {
    supported,
    phase: readonly(phase),
    progress: readonly(progress),
    activePath: readonly(activePath),
    lastFailedTransferId: readonly(lastFailedTransferId),
    sendDirect,
    loadDirectFile,
  }
}

function createPeer(): RTCPeerConnection {
  // LAN-first default: no public STUN/TURN request, so ICE host candidates stay
  // inside the local deployment. The authenticated node remains the fallback path.
  return new RTCPeerConnection({ iceServers: [] })
}

async function waitForIceGathering(peer: RTCPeerConnection, signal?: AbortSignal): Promise<void> {
  if (peer.iceGatheringState === 'complete') return
  throwIfDirectAborted(signal)
  await new Promise<void>((resolve) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      peer.removeEventListener('icegatheringstatechange', onChange)
      signal?.removeEventListener('abort', finish)
      window.clearTimeout(timer)
      resolve()
    }
    const onChange = () => {
      if (peer.iceGatheringState === 'complete') finish()
    }
    const timer = window.setTimeout(finish, ICE_GATHERING_TIMEOUT_MS)
    peer.addEventListener('icegatheringstatechange', onChange)
    signal?.addEventListener('abort', finish, { once: true })
  })
  throwIfDirectAborted(signal)
}

async function waitForBuffer(channel: RTCDataChannel): Promise<void> {
  if (channel.bufferedAmount <= BUFFER_HIGH_WATER) return
  await new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      cleanup()
      reject(new Error('直传发送缓冲区长时间未释放'))
    }, 5_000)
    const onLow = () => {
      cleanup()
      resolve()
    }
    const cleanup = () => {
      window.clearTimeout(timeout)
      channel.removeEventListener('bufferedamountlow', onLow)
    }
    channel.addEventListener('bufferedamountlow', onLow, { once: true })
  })
}

function safeMime(value: string): string {
  const normalized = value.trim().toLowerCase()
  return /^[a-z0-9][a-z0-9.+-]{0,63}\/[a-z0-9][a-z0-9.+-]{0,127}$/.test(normalized)
    ? normalized
    : 'application/octet-stream'
}

function requestId(): string {
  return `req_${createClientMessageId()}`
}

function asError(cause: unknown, fallback: string): Error {
  return cause instanceof Error ? cause : new Error(fallback)
}

function throwIfDirectAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('设备直传已取消', 'AbortError')
}
