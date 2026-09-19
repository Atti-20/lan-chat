export const TEXT_AVATAR_COLOR_PRESETS = [
  '#5AC8FA', '#007AFF', '#5856D6', '#AF52DE',
  '#FF2D55', '#FF3B30', '#FF9500', '#FFCC00',
  '#34C759', '#30D158', '#00C7BE', '#64748B',
] as const

// The registration preview and the profile editor must begin on the same
// persisted colour.  Keeping it explicit prevents a later theme or hash
// change from making a freshly-created text avatar look different elsewhere.
export const DEFAULT_TEXT_AVATAR_COLOR = '#5856D6'

export function textAvatarInitial(name: string | null | undefined): string {
  return name?.trim().slice(0, 1).toUpperCase() || '?'
}

export function createTextAvatar(
  name: string | null | undefined,
  color = DEFAULT_TEXT_AVATAR_COLOR,
): string {
  return `letter:${textAvatarInitial(name)}:${color}`
}

export function isTextAvatar(avatar: string | null | undefined): boolean {
  return !avatar || avatar === 'text' || avatar.startsWith('letter:')
}
