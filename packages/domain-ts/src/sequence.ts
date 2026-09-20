/**
 * Advances a receive cursor only through a fully contiguous run.
 *
 * Cached/history pages may start far ahead of the durable cursor. Treating
 * their maximum sequence as received would permanently hide the missing range
 * from the next SYNC_REQUEST.
 */
export function advanceContiguousSequence(
  current: number,
  candidates: Iterable<number>,
): number {
  let cursor = Number.isSafeInteger(current) && current > 0 ? current : 0
  const ordered = [...new Set(candidates)]
    .filter((sequence) => Number.isSafeInteger(sequence) && sequence > 0)
    .sort((first, second) => first - second)

  for (const sequence of ordered) {
    if (sequence <= cursor) continue
    if (sequence !== cursor + 1) break
    cursor = sequence
  }
  return cursor
}
