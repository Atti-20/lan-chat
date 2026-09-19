import type { RealtimePort, RealtimeSocket } from '../../../../../packages/platform-ports/src/realtime'
import { webSocketUrl } from '../nodeContext'

// Node URL selection remains shared with the existing HTTP/native facade.
export const webRealtime: RealtimePort = {
  async open(): Promise<RealtimeSocket> {
    return new WebSocket(webSocketUrl()) as unknown as RealtimeSocket
  },
}
