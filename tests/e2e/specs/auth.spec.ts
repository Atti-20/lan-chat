import { expect, test } from '@playwright/test'
import { ApiClient, uniqueId } from '../src/api.js'

const instanceA = process.env.E2E_INSTANCE_A_URL || 'http://127.0.0.1:18081'

test('web registration, login, HttpOnly refresh rotation, and logout', async () => {
  const api = new ApiClient(instanceA)
  const suffix = uniqueId('auth').replace(/_/g, '').slice(-16)
  const username = `e2e_auth_${suffix}`
  const password = 'E2ePassword2026'

  await api.register(username, password, `Auth ${suffix}`.slice(0, 16))
  const loggedIn = await api.login(username, password, 'E2E browser initial')

  expect(loggedIn.username).toBe(username)
  expect(loggedIn.token).toBeTruthy()
  expect(loggedIn as unknown as Record<string, unknown>).not.toHaveProperty('refreshToken')
  expect(api.cookieNames()).toEqual(['lanchat_refresh'])

  const refreshed = await api.refresh('E2E browser refreshed')
  expect(refreshed.userId).toBe(loggedIn.userId)
  expect(refreshed.token).not.toBe(loggedIn.token)
  expect(refreshed as unknown as Record<string, unknown>).not.toHaveProperty('refreshToken')
  await expect(api.me(refreshed.token)).resolves.toMatchObject({ username })

  await api.logout(refreshed.token)
  expect(api.hasCookie('lanchat_refresh')).toBe(false)
  await expect(api.me(refreshed.token)).rejects.toThrow(/401/)
})

test('different users can perform first login concurrently without device-session deadlocks', async () => {
  const suffix = uniqueId('parallel').replace(/_/g, '').slice(-12)
  const password = 'E2ePassword2026'
  const candidates = Array.from({ length: 6 }, (_, index) => ({
    api: new ApiClient(instanceA),
    username: `e2e_p${index}_${suffix}`,
    nickname: `P${index} ${suffix}`.slice(0, 16),
  }))

  await Promise.all(candidates.map(({ api, username, nickname }) =>
    api.register(username, password, nickname)))
  const sessions = await Promise.all(candidates.map(({ api, username }, index) =>
    api.login(username, password, `E2E first-login race ${index}`)))

  expect(sessions.map((session) => session.username))
    .toEqual(candidates.map(({ username }) => username))
  expect(new Set(sessions.map((session) => session.userId)).size)
    .toBe(candidates.length)
})
