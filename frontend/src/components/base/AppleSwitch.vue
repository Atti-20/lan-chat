<script setup lang="ts">
interface Props {
  modelValue: boolean
  disabled?: boolean
  ariaLabel?: string
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  ariaLabel: '切换选项',
})

const emit = defineEmits<{
  'update:modelValue': [enabled: boolean]
}>()

function toggle(): void {
  if (!props.disabled) emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <button
    class="apple-switch"
    :class="{ 'apple-switch--on': modelValue }"
    type="button"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="ariaLabel"
    :disabled="disabled"
    @click="toggle"
  >
    <span aria-hidden="true" />
  </button>
</template>

<style scoped>
.apple-switch {
  display: inline-flex;
  width: 51px;
  height: 31px;
  flex: 0 0 auto;
  padding: 2px;
  align-items: center;
  border: 0;
  border-radius: 999px;
  background: var(--separator-strong);
  cursor: pointer;
  transition: background-color 180ms var(--ease-liquid), transform 140ms var(--ease-liquid);
}

.apple-switch > span {
  display: block;
  width: 27px;
  height: 27px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.24), 0 3px 8px rgba(0, 0, 0, 0.12);
  transition: transform 180ms var(--ease-liquid), width 140ms var(--ease-liquid);
}

.apple-switch--on { background: var(--green); }
.apple-switch--on > span { transform: translateX(20px); }
.apple-switch:active:not(:disabled) > span { width: 30px; }
.apple-switch--on:active:not(:disabled) > span { transform: translateX(17px); }
.apple-switch:disabled { cursor: default; opacity: 0.48; }

@media (prefers-reduced-motion: reduce) {
  .apple-switch,
  .apple-switch > span { transition: none; }
}
</style>
