/**
 * 生成 32 位客户端消息 ID。
 *
 * 部分旧版浏览器虽然提供 Web Crypto，但尚未实现 randomUUID()。
 * 消息 ID 只要求足够唯一，因此在缺少原生 UUID 时依次回退到
 * getRandomValues() 和普通随机数。
 */
export function createClientMessageId(): string {
  const browserCrypto = globalThis.crypto
  if (typeof browserCrypto?.randomUUID === 'function') {
    return browserCrypto.randomUUID().replace(/-/g, '')
  }

  const bytes = new Uint8Array(16)
  if (typeof browserCrypto?.getRandomValues === 'function') {
    browserCrypto.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }

  // RFC 4122 version 4 / variant 1 位，保持与标准 UUID 相同的结构。
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}
