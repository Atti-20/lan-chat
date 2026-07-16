import { readonly, shallowRef } from 'vue'
import { api } from '../services/api'
import type { TemporaryRoom, TemporaryRoomCreatePayload } from '../types'

interface UseTemporaryRoomsOptions {
  onChanged?: (room: TemporaryRoom) => void | Promise<void>
}

export function useTemporaryRooms(options: UseTemporaryRoomsOptions = {}) {
  const saving = shallowRef(false)

  async function create(payload: TemporaryRoomCreatePayload): Promise<TemporaryRoom> {
    saving.value = true
    try {
      const room = await api.rooms.create(payload)
      await options.onChanged?.(room)
      return room
    } finally {
      saving.value = false
    }
  }

  async function join(roomCode: string): Promise<TemporaryRoom> {
    saving.value = true
    try {
      const room = await api.rooms.join(roomCode.trim().toUpperCase())
      await options.onChanged?.(room)
      return room
    } finally {
      saving.value = false
    }
  }

  async function leave(roomId: number): Promise<void> {
    saving.value = true
    try {
      await api.rooms.leave(roomId)
    } finally {
      saving.value = false
    }
  }

  return {
    saving: readonly(saving),
    create,
    join,
    leave,
  }
}
