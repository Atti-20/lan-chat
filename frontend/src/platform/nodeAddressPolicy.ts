/**
 * 明文 HTTP 只在局域网可信范围内有意义；公网地址一律要求 HTTPS，
 * 防止 LAN 构建把凭据发往公网明文端点。
 */
export function isPrivateLanHost(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase()
  if (!host) return false
  if (host === 'localhost' || host.endsWith('.localhost')) return true
  if (host.endsWith('.local')) return true

  const ipv4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
  if (ipv4) {
    const octets = ipv4.slice(1).map(Number)
    if (octets.some((octet) => octet > 255)) return false
    const [first, second] = octets
    return first === 127
      || first === 10
      || (first === 172 && second >= 16 && second <= 31)
      || (first === 192 && second === 168)
      || (first === 169 && second === 254)
  }

  if (host.includes(':')) {
    if (host === '::1') return true
    return /^fe[89ab]/.test(host) || /^f[cd]/.test(host)
  }
  return false
}

export function parseVerifiableNodeAddress(address: string): URL {
  let target: URL
  try {
    target = new URL(address.trim())
  } catch {
    // 例如 Android 发现回退产出的 IPv6 zone-id（fe80::1%wlan0）：
    // WHATWG URL 无法解析，映射为友好提示而不是英文 TypeError。
    throw new Error('节点地址必须是有效的 HTTP 或 HTTPS 地址')
  }
  if (!['http:', 'https:'].includes(target.protocol)
    || target.username
    || target.password
    || !target.hostname) {
    throw new Error('节点地址必须是有效的 HTTP 或 HTTPS 地址')
  }
  if (target.protocol === 'http:' && !isPrivateLanHost(target.hostname)) {
    throw new Error('HTTP 明文节点仅允许本机、私有网段或 .local 局域网地址；公网节点请使用 HTTPS')
  }
  return target
}
