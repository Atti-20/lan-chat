import { computed, onBeforeUnmount, readonly, shallowRef } from 'vue'
import { loadDirectFile, moveDirectFile, saveDirectFile } from '../services/localChatDb'
import { subscribeRealtimeEvents } from '../services/realtimeEvents'
import type { DirectFileRecord, FileAttachmentData, WsEnvelope } from '../types'
import { createClientMessageId } from '../utils/id'

const CHUNK_SIZE = 64 * 1024
const BUFFER_HIGH_WATER = 4 * 1024 * 1024
const NEGOTIATION_TIMEOUT_MS = 12_000
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
  const supported = computed(() => typeof RTCPeerConnection !== 'undefined'
    && typeof crypto?.subtle?.digest === 'function')

  const outgoing = new Map<string, OutgoingTransfer>()
  const incoming = new Map<string, IncomingTransfer>()
  const unsubscribe = subscribeRealtimeEvents(handleRealtimeEvent)

  onBeforeUnmount(() => {
    unsubscribe()
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

    phase.value = 'HASHING'
    progress.value = 0
    activePath.value = 'PEER_TO_PEER'
    lastFailedTransferId.value = null
    const transferId = createClientMessageId()
    const fileHash = await sha256(file)
    const mime = safeMime(file.type)
    await saveDirectFile({
      transferId,
      name: file.name,
      mime,
      size: file.size,
      fileHash,
      blob: file,
      savedAt: new Date().toISOString(),
    })

    const peer = createPeer()
    const channel = peer.createDataChannel('lanchat-file', { ordered: true })
    channel.binaryType = 'arraybuffer'
    channel.bufferedAmountLowThreshold = BUFFER_HIGH_WATER / 2

    const result = new Promise<FileAttachmentData>((resolve, reject) => {
      const transfer: OutgoingTransfer = {
        transferId,
        peer,
        channel,
        file,
        fileHash,
        conversationId,
        toUserId,
        resolve,
        reject,
        sending: false,
        serverAssigned: false,
        timer: window.setTimeout(() => {
          failOutgoing(transfer, new Error('设备直传协商超时'))
        }, NEGOTIATION_TIMEOUT_MS),
      }
      outgoing.set(transferId, transfer)
      channel.onopen = () => {
        window.clearTimeout(transfer.timer)
        transfer.timer = window.setTimeout(() => {
          failOutgoing(transfer, new Error('设备直传超时'))
        }, TRANSFER_TIMEOUT_MS)
        void streamOutgoing(transfer)
      }
      channel.onerror = () => failOutgoing(transfer, new Error('设备直传通道异常'))
      channel.onclose = () => {
        if (outgoing.has(transfer.transferId) && !transfer.sending) {
          failOutgoing(transfer, new Error('设备直传通道已关闭'))
        }
      }
    })

    try {
      phase.value = 'NEGOTIATING'
      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      await waitForIceGathering(peer)
      const sent = options.sendEvent('FILE_TRANSFER_OFFER', {
        transferId,
        toUserId,
        name: file.name,
        size: file.size,
        mime,
        fileHash,
        sdp: peer.localDescription?.sdp || offer.sdp,
      }, { requestId: requestId(), conversationId })
      if (!sent) throw new Error('实时连接不可用')
      return await result
    } catch (cause) {
      const transfer = outgoing.get(transferId)
      if (transfer) failOutgoing(transfer, asError(cause, '设备直传失败'))
      throw cause
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
      const actualHash = await sha256(blob)
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
      options.sendEvent('FILE_TRANSFER_COMPLETE', {
        transferId: transfer.transferId,
        fileHash: transfer.fileHash,
        fileSize: transfer.receivedBytes,
      }, { requestId: requestId(), conversationId: transfer.conversationId })
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

async function waitForIceGathering(peer: RTCPeerConnection): Promise<void> {
  if (peer.iceGatheringState === 'complete') return
  await new Promise<void>((resolve) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      peer.removeEventListener('icegatheringstatechange', onChange)
      window.clearTimeout(timer)
      resolve()
    }
    const onChange = () => {
      if (peer.iceGatheringState === 'complete') finish()
    }
    const timer = window.setTimeout(finish, ICE_GATHERING_TIMEOUT_MS)
    peer.addEventListener('icegatheringstatechange', onChange)
  })
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

async function sha256(data: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await data.arrayBuffer())
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('')
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
