/** A browser/WebView cache record, not a transport or shared domain model. */
export interface DirectFileRecord {
  transferId: string
  name: string
  mime: string
  size: number
  fileHash: string
  blob: Blob
  savedAt: string
}
