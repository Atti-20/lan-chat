<script setup lang="ts">
type BadgeTone = 'unread' | 'pending' | 'neutral' | 'danger'

interface Props {
  value?: string | number
  tone?: BadgeTone
  label?: string
}

withDefaults(defineProps<Props>(), {
  value: '',
  tone: 'neutral',
  label: '',
})
</script>

<template>
  <span
    class="ui-badge"
    :class="`ui-badge--${tone}`"
    :aria-label="label || undefined"
  ><slot>{{ value }}</slot></span>
</template>

<style scoped>
.ui-badge {
  display: inline-grid;
  min-width: var(--mx-component-badge-min-size);
  min-height: var(--mx-component-badge-min-size);
  padding-inline: var(--mx-component-badge-padding-inline);
  place-items: center;
  border-radius: var(--mx-shape-radius-pill);
  color: var(--mx-color-text-on-accent);
  font-size: var(--mx-component-badge-font-size);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  line-height: 1;
  white-space: nowrap;
}

.ui-badge--unread,
.ui-badge--danger { background: var(--mx-color-action-danger); }

.ui-badge--pending {
  color: var(--mx-color-action-text);
  background: var(--mx-color-interaction-selected);
}

.ui-badge--neutral {
  color: var(--mx-color-text-secondary);
  background: var(--mx-color-background-fill);
}
</style>
