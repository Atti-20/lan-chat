<script setup lang="ts">
import { useToast } from '../../composables/useToast'

const toast = useToast()
</script>

<template>
  <div class="toast-region" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <button
        v-for="item in toast.items.value"
        :key="item.id"
        class="toast glass-surface"
        :class="`toast--${item.tone}`"
        type="button"
        @click="toast.remove(item.id)"
      >
        <span class="toast-dot" />
        {{ item.message }}
      </button>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-region {
  position: fixed;
  z-index: 1000;
  top: max(20px, env(safe-area-inset-top));
  left: 50%;
  display: grid;
  width: min(92vw, 420px);
  gap: 10px;
  pointer-events: none;
  transform: translateX(-50%);
}

.toast {
  display: flex;
  min-height: 48px;
  padding: 12px 16px;
  align-items: center;
  gap: 10px;
  border-radius: 17px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 650;
  text-align: left;
  pointer-events: auto;
  cursor: pointer;
}

.toast-dot {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--blue);
  box-shadow: 0 0 0 5px rgba(10, 132, 255, 0.12);
}

.toast--success .toast-dot { background: var(--green); box-shadow: 0 0 0 5px rgba(48, 209, 88, 0.12); }
.toast--warning .toast-dot { background: #ff9f0a; box-shadow: 0 0 0 5px rgba(255, 159, 10, 0.12); }
.toast--danger .toast-dot { background: var(--coral); box-shadow: 0 0 0 5px rgba(255, 107, 107, 0.12); }

.toast-enter-active,
.toast-leave-active { transition: all 240ms var(--ease-liquid); }
.toast-enter-from,
.toast-leave-to { opacity: 0; transform: translateY(-14px) scale(0.96); }
</style>
