import type { operations, paths, components } from '../../packages/protocol/src/rest'
import { REST_OPERATIONS, type ApiResult, type DeviceType } from '../../packages/protocol/src/rest-contract'
import type { WsEnvelope } from '../../packages/protocol/src/envelope'

const nullableMetadata: WsEnvelope = { version: 1, timestamp: 1, event: 'AUTH', payload: { token: 'fixture' }, requestId: null }
void nullableMetadata

const login: components['schemas']['LoginDTO'] = { username: 'alice', password: 'fixture-password', deviceName: null }
// @ts-expect-error A password is required by the actual login service.
const noPassword: typeof login = { username: 'alice' }
// @ts-expect-error Optional device name can be null, required password cannot.
const nullPassword: typeof login = { username: 'alice', password: null }
const absentData: ApiResult<components['schemas']['LoginVO']> = { code: 200, msg: 'success' }
const nullData: typeof absentData = { ...absentData, data: null }
const device: DeviceType = 'desktop'
const loginMethod: 'POST' = REST_OPERATIONS.login.method
const historyMethod: 'GET' = REST_OPERATIONS.getConversationHistory.method
void [login, noPassword, nullPassword, absentData, nullData, device, loginMethod, historyMethod]

const register: components['schemas']['RegisterDTO'] = { username: 'alice', password: 'Password123', nickname: null }
// @ts-expect-error Registration also requires credentials in the real service.
const emptyRegister: typeof register = {}
void [register, emptyRegister]

const upload: operations['upload']['requestBody']['content']['multipart/form-data'] = {
  file: new Blob(['fixture']), conversationId: 'private:1:2',
}
void upload
// @ts-expect-error Binary content must not be accidentally encoded as JSON/base64 text.
const wrong: typeof upload = { file: 'base64-string', conversationId: 'private:1:2' }
void wrong

// A stream authorization failure can have no body; the adapter must check before parsing.
const emptyDenied: operations['getFileContent']['responses'][403] = { headers: {} }
const jsonDenied: operations['getFileContent']['responses'][403] = {
  headers: {}, content: { 'application/json': { code: 403, msg: 'denied', data: null } },
}
void emptyDenied
void jsonDenied
const regularDenied: paths['/api/v1/chat/conversations']['get']['responses'][401] = {
  headers: {}, content: { 'application/json': { code: 401, msg: 'expired' } },
}
void regularDenied

function parseStreamingFailure(response: typeof jsonDenied) {
  // @ts-expect-error The body can be absent; a generated adapter must check before decoding.
  return response.content['application/json']
}
void parseStreamingFailure
