import { createHash } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { ApiClient, createFriendPair, createUser, uniqueId } from '../src/api.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'

function sha256(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex')
}

test('multipart upload verifies every chunk and signed download bytes', async () => {
  test.setTimeout(120_000)
  const pair = await createFriendPair({ aliceUrl: instanceA, bobUrl: instanceA })
  const fileSize = 8 * 1024 * 1024 + 257
  const bytes = Buffer.alloc(fileSize, 0x61)
  bytes.set(Buffer.from('\nLANChat resumable E2E\n'), fileSize - 23)
  const fileHash = sha256(bytes)
  const clientUploadId = uniqueId('multipart')

  const initialized = await pair.aliceApi.initializeUpload(pair.alice.token, {
    clientUploadId,
    conversationId: pair.conversationId,
    fileName: `${clientUploadId}.txt`,
    fileSize,
    fileType: 'text/plain',
    fileHash,
  })
  expect(initialized.status).toBe('UPLOADING')
  expect(initialized.totalParts).toBeGreaterThanOrEqual(2)
  expect(initialized.uploadedParts).toEqual([])

  for (let partNumber = 1; partNumber <= initialized.totalParts; partNumber += 1) {
    const start = (partNumber - 1) * initialized.chunkSize
    const end = Math.min(start + initialized.chunkSize, bytes.length)
    const part = bytes.subarray(start, end)
    const status = await pair.aliceApi.uploadPart(
      pair.alice.token,
      initialized.uploadId,
      partNumber,
      sha256(part),
      part,
    )
    expect(status.uploadedParts).toContain(partNumber)
  }

  const completed = await pair.aliceApi.completeUpload(
    pair.alice.token,
    initialized.uploadId,
  )
  expect(completed.fileHash).toBe(fileHash)
  expect(completed.fileSize).toBe(fileSize)
  expect(completed.fileName).toBeTruthy()

  const signedUrl = await pair.aliceApi.previewUrl(
    pair.alice.token,
    completed.fileName,
  )
  expect(new URL(signedUrl, instanceA).pathname).toContain('/api/v1/file/preview/')
  const downloaded = await pair.aliceApi.downloadSigned(signedUrl)
  expect(downloaded.status).toBe(200)
  expect(downloaded.headers.get('cache-control')).toContain('no-store')
  expect(downloaded.headers.get('x-content-type-options')).toBe('nosniff')
  const downloadedBytes = Buffer.from(await downloaded.arrayBuffer())
  expect(downloadedBytes.length).toBe(fileSize)
  expect(sha256(downloadedBytes)).toBe(fileHash)
})

test('archiving the first uploader preserves another users deduplicated avatar', async () => {
  const firstApi = new ApiClient(instanceA)
  const secondApi = new ApiClient(instanceA)
  const firstUser = await createUser(firstApi, 'e2e_file_owner')
  const secondUser = await createUser(secondApi, 'e2e_file_shared')
  const image = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64',
  )

  const firstUpload = await firstApi.uploadAvatar(
    firstUser.session.token,
    `${uniqueId('avatar_owner')}.png`,
    'image/png',
    image,
  )
  const sharedUpload = await secondApi.uploadAvatar(
    secondUser.session.token,
    `${uniqueId('avatar_shared')}.png`,
    'image/png',
    image,
  )
  expect(firstUpload.instantUpload).toBe(false)
  expect(sharedUpload.instantUpload).toBe(true)
  expect(sharedUpload.id).toBe(firstUpload.id)

  const avatar = sharedUpload.thumbnailUrl || sharedUpload.url
  const profile = await secondApi.updateProfile(
    secondUser.session.token,
    { avatar },
  )
  expect(profile.avatar).toBe(avatar)

  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login(
    'admin',
    process.env.LANCHAT_BOOTSTRAP_ADMIN_PASSWORD || 'E2eAdminPassword-2026',
    'LANChat E2E administrator',
  )
  await adminApi.archiveUser(admin.token, firstUser.session.userId)

  const signedUrl = await secondApi.previewUrl(
    secondUser.session.token,
    sharedUpload.fileName,
  )
  const downloaded = await secondApi.downloadSigned(signedUrl)
  expect(downloaded.status).toBe(200)
  expect(sha256(Buffer.from(await downloaded.arrayBuffer()))).toBe(sha256(image))
})

test('administrator broadcast records recipient view, confirmation, and statistics', async () => {
  const recipientApi = new ApiClient(instanceA)
  const recipient = await createUser(recipientApi, 'e2e_broadcast')
  const adminApi = new ApiClient(instanceA)
  const admin = await adminApi.login(
    'admin',
    process.env.LANCHAT_BOOTSTRAP_ADMIN_PASSWORD || 'E2eAdminPassword-2026',
    'LANChat E2E administrator',
  )

  const broadcast = await adminApi.createBroadcast(admin.token, {
    title: `E2E broadcast ${Date.now().toString(36)}`,
    content: 'Confirm this E2E broadcast to prove recipient statistics.',
    scopeType: 'ALL',
    confirmationRequired: true,
    confirmationOptions: ['EXECUTED'],
  })
  expect(broadcast.status).toBe('ACTIVE')

  const viewed = await recipientApi.viewBroadcast(recipient.session.token, broadcast.id)
  expect(viewed.userId).toBe(recipient.session.userId)
  expect(viewed.viewedAt).toBeTruthy()

  const confirmed = await recipientApi.confirmBroadcast(
    recipient.session.token,
    broadcast.id,
    'EXECUTED',
  )
  expect(confirmed.confirmStatus).toBe('EXECUTED')
  expect(confirmed.confirmedAt).toBeTruthy()

  const stats = await adminApi.broadcastStats(admin.token, broadcast.id)
  expect(stats.broadcastId).toBe(broadcast.id)
  expect(stats.targetCount).toBeGreaterThanOrEqual(1)
  expect(stats.viewedCount).toBeGreaterThanOrEqual(1)
  expect(stats.confirmedCount).toBe(1)
  expect(stats.executedCount).toBe(1)
  expect(stats.unconfirmedCount).toBe(stats.targetCount - 1)
  expect(stats.confirmationCounts.EXECUTED).toBe(1)
})
