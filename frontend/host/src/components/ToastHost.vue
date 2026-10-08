<script setup lang="ts">
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div
        v-for="t in ui.toasts"
        :key="t.id"
        class="toast"
        :class="t.kind"
        @click="ui.dismiss(t.id)"
      >
        <span class="icon">{{ t.kind === 'error' ? '⚠' : t.kind === 'success' ? '✓' : 'ℹ' }}</span>
        <span class="msg">{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  bottom: 38px;
  right: 18px;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  z-index: 200;
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  max-width: 380px;
  padding: 9px 13px;
  border-radius: var(--radius);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow);
  font-size: 13px;
  cursor: pointer;
}
.toast .icon {
  flex: 0 0 auto;
}
.toast.error {
  border-color: color-mix(in srgb, var(--danger) 45%, transparent);
  color: var(--danger);
}
.toast.success {
  border-color: color-mix(in srgb, var(--success) 45%, transparent);
}
.toast-enter-active,
.toast-leave-active {
  transition: all 0.18s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(16px);
}
</style>
