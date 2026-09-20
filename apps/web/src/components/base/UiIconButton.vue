<script setup lang="ts">
import UiIcon, { type IconName } from './UiIcon.vue'

interface Props {
  name: IconName
  label: string
  active?: boolean
  disabled?: boolean
  loading?: boolean
  type?: 'button' | 'submit' | 'reset'
  size?: 'default' | 'compact'
  variant?: 'plain' | 'accent'
}

const props = withDefaults(defineProps<Props>(), {
  active: false,
  disabled: false,
  loading: false,
  type: 'button',
  size: 'default',
  variant: 'plain',
})
</script>

<template>
  <button
    class="ui-icon-button"
    :class="[
      `ui-icon-button--${size}`,
      `ui-icon-button--${variant}`,
      { 'ui-icon-button--active': active, 'ui-icon-button--loading': loading },
    ]"
    :type="type"
    :disabled="disabled || loading"
    :aria-label="label"
    :aria-pressed="active ? 'true' : undefined"
    :aria-busy="loading || undefined"
  >
    <UiIcon :name="name" />
  </button>
</template>

<style scoped>
.ui-icon-button {
  display: inline-grid;
  width: var(--mx-component-icon-button-size);
  height: var(--mx-component-icon-button-size);
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--mx-shape-radius-control);
  color: var(--mx-color-text-primary);
  background: transparent;
  cursor: pointer;
  transition: transform var(--mx-motion-duration-fast) var(--mx-motion-easing-standard), background-color var(--mx-motion-duration-fast) ease, color var(--mx-motion-duration-fast) ease;
}

.ui-icon-button--compact {
  width: var(--mx-size-control-compact);
  height: var(--mx-size-control-compact);
}

.ui-icon-button :deep(.ui-icon) {
  width: var(--mx-component-icon-button-icon-size) !important;
  height: var(--mx-component-icon-button-icon-size) !important;
}

.ui-icon-button:hover:not(:disabled),
.ui-icon-button--active { color: var(--mx-color-action-text); background: var(--mx-color-interaction-control-hover); }
.ui-icon-button--accent { color: var(--mx-color-text-on-accent); background: var(--mx-color-action-primary); box-shadow: inset 0 1px 0 var(--mx-color-highlight-default); }
.ui-icon-button--accent:hover:not(:disabled) { color: var(--mx-color-text-on-accent); background: var(--mx-color-action-primary-hover); }
.ui-icon-button:active:not(:disabled) { transform: scale(var(--mx-component-icon-button-press-scale)); }
.ui-icon-button:focus-visible { outline: 3px solid var(--mx-color-focus-ring); outline-offset: 2px; }
.ui-icon-button:disabled { cursor: not-allowed; opacity: 0.45; }

.ui-icon-button--loading :deep(.ui-icon) { animation: ui-icon-button-loading var(--mx-motion-duration-fast) linear infinite; }

@keyframes ui-icon-button-loading {
  to { transform: rotate(1turn); }
}

@media (prefers-reduced-motion: reduce) {
  .ui-icon-button { transition: none; }
  .ui-icon-button--loading :deep(.ui-icon) { animation: none; }
}
</style>
