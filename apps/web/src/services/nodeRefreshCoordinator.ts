export class NodeRefreshCoordinator<T> {
  private readonly inFlight = new Map<string, Promise<boolean>>()

  run(
    nodeKey: string,
    refresh: () => Promise<T | null>,
    isCurrent: (expectedNodeKey: string) => boolean,
    commit: (value: T) => void,
  ): Promise<boolean> {
    const existing = this.inFlight.get(nodeKey)
    if (existing) return existing

    const operation = (async () => {
      try {
        const value = await refresh()
        if (value == null || !isCurrent(nodeKey)) return false
        commit(value)
        return true
      } catch {
        return false
      }
    })()
    const tracked = operation.finally(() => {
      if (this.inFlight.get(nodeKey) === tracked) this.inFlight.delete(nodeKey)
    })
    this.inFlight.set(nodeKey, tracked)
    return tracked
  }
}
