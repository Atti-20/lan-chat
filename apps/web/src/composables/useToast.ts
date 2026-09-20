import { readonly, ref } from 'vue'

export type ToastTone = 'default' | 'success' | 'warning' | 'danger'

export interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

const items = ref<ToastItem[]>([])
let nextId = 1

export function useToast() {
  function push(message: string, tone: ToastTone = 'default', duration = 3200): void {
    const id = nextId++
    items.value.push({ id, message, tone })
    window.setTimeout(() => remove(id), duration)
  }

  function remove(id: number): void {
    items.value = items.value.filter((item) => item.id !== id)
  }

  return {
    items: readonly(items),
    push,
    remove,
  }
}
