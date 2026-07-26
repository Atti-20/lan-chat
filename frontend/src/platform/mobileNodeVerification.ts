import { CapacitorHttp } from '@capacitor/core'
import type { NodePublicInfo } from '../types'
import type { DesktopNode } from './nativeBridge'
import { isCapacitorRuntime } from './mobileRuntime'
import { parseVerifiableNodeAddress } from './nodeAddressPolicy'

interface NodeInfoResponse {
  code: number
  msg: string
  data?: NodePublicInfo
}

export async function verifyMobileNode(address: string): Promise<DesktopNode> {
  const target = parseVerifiableNodeAddress(address)

  const origin = target.origin
  let result: NodeInfoResponse
  let responseOk: boolean
  try {
    const requestUrl = new URL('/api/v1/node/info', origin).toString()
    const headers = { 'X-Request-ID': `node_${crypto.randomUUID?.() || Date.now()}` }
    if (isCapacitorRuntime()) {
      const response = await CapacitorHttp.get({
        url: requestUrl,
        headers,
        readTimeout: 8_000,
        connectTimeout: 8_000,
        responseType: 'json',
        // 握手不得被重定向带离用户输入的 origin（例如被劫持到公网地址）。
        disableRedirects: true,
      })
      result = typeof response.data === 'string'
        ? JSON.parse(response.data) as NodeInfoResponse
        : response.data as NodeInfoResponse
      responseOk = response.status >= 200 && response.status < 300
    } else {
      const response = await fetch(requestUrl, { headers, redirect: 'error' })
      result = await response.json() as NodeInfoResponse
      responseOk = response.ok
    }
  } catch {
    throw new Error('节点返回了无法识别的握手信息')
  }
  if (!responseOk || result.code !== 200 || !result.data) {
    throw new Error(result.msg || '节点握手失败')
  }
  const info = result.data
  if (info.protocolVersion !== 1 || !info.nodeId || !info.apiBasePath || !info.webSocketPath) {
    throw new Error('节点协议不兼容，无法连接')
  }

  return {
    nodeId: info.nodeId,
    nodeName: info.nodeName,
    organizationName: info.organizationName,
    version: info.version,
    mode: info.mode,
    appUrl: new URL(info.appPath || '/app/', origin).toString(),
    secure: target.protocol === 'https:',
    current: false,
    lastSeenAt: new Date().toISOString(),
    source: 'MANUAL',
    health: 'HEALTHY',
    latencyMs: null,
    failureCount: 0,
    pinned: false,
    protocolVersion: info.protocolVersion,
    apiOrigin: origin,
    apiBasePath: info.apiBasePath,
    webSocketPath: info.webSocketPath,
    healthPath: info.healthPath,
    appPath: info.appPath,
  }
}
