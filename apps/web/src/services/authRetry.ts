export interface AuthRetryDeps {
  currentNodeKey: () => string
  refreshAccessToken: (expectedNodeKey: string) => Promise<boolean>
  clearSession: () => void
}

export type UnauthorizedOutcome = 'retry' | 'expired'

/**
 * 收到 401 后的统一决策：刷新成功且期间没有切换节点才允许重试一次；
 * 只有仍停留在发起请求的节点上才清理会话，避免误清新节点的登录状态。
 */
export async function resolveUnauthorized(
  deps: AuthRetryDeps,
  requestNodeKey: string,
): Promise<UnauthorizedOutcome> {
  if (await deps.refreshAccessToken(requestNodeKey)
    && deps.currentNodeKey() === requestNodeKey) {
    return 'retry'
  }
  if (deps.currentNodeKey() === requestNodeKey) deps.clearSession()
  return 'expired'
}
