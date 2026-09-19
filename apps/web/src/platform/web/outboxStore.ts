import type { OutboxPort } from '../../../../../packages/platform-ports/src/outbox'
import { deleteOutboxEntry, loadOutbox, saveOutboxEntry } from '../../services/localChatDb'

// Web and Tauri's WebView retain the same existing IndexedDB records and version.
// Vue proxy snapshots and database transactions remain inside localChatDb.
export const browserOutboxStore: OutboxPort = {
  load: loadOutbox,
  save: saveOutboxEntry,
  delete: deleteOutboxEntry,
}
