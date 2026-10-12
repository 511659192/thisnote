<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  busy?: boolean
}>(), {
  confirmLabel: '确认',
  cancelLabel: '取消',
  busy: false,
})

const emit = defineEmits<{
  (event: 'confirm'): void
  (event: 'cancel'): void
}>()

const cancelButton = ref<HTMLButtonElement | null>(null)
const confirmButton = ref<HTMLButtonElement | null>(null)

watch(() => props.open, async (open) => {
  if (!open) return
  await nextTick()
  cancelButton.value?.focus()
})

function onTab(event: KeyboardEvent) {
  if (event.shiftKey && document.activeElement === cancelButton.value) {
    event.preventDefault()
    confirmButton.value?.focus()
  } else if (!event.shiftKey && document.activeElement === confirmButton.value) {
    event.preventDefault()
    cancelButton.value?.focus()
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="confirm-overlay" @click.self="!busy && emit('cancel')">
      <section
        class="confirm-dialog pop-in"
        role="alertdialog"
        aria-modal="true"
        :aria-label="title"
        tabindex="-1"
        @keydown.esc.stop.prevent="!busy && emit('cancel')"
        @keydown.tab="onTab"
      >
        <div class="confirm-mark" aria-hidden="true">!</div>
        <div class="confirm-copy">
          <h3>{{ title }}</h3>
          <p>{{ message }}</p>
        </div>
        <div class="confirm-actions">
          <button
            ref="cancelButton"
            type="button"
            class="confirm-button cancel"
            :disabled="busy"
            @click="emit('cancel')"
          >
            {{ cancelLabel }}
          </button>
          <button
            ref="confirmButton"
            type="button"
            class="confirm-button danger"
            :disabled="busy"
            @click="emit('confirm')"
          >
            {{ busy ? '处理中…' : confirmLabel }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 48%);
  backdrop-filter: blur(2px);
}
.confirm-dialog {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 4px 12px;
  width: min(440px, 100%);
  padding: 22px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-lg);
  outline: none;
}
.confirm-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--warning) 16%, transparent);
  color: var(--warning);
  font-weight: 800;
}
.confirm-copy h3 {
  margin: 2px 0 7px;
  font-size: 15px;
  font-weight: 700;
}
.confirm-copy p {
  margin: 0;
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-line;
}
.confirm-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
.confirm-button {
  min-width: 76px;
  padding: 7px 13px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.confirm-button.cancel {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}
.confirm-button.cancel:hover:not(:disabled) {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.confirm-button.danger {
  border-color: var(--danger);
  background: var(--danger);
  color: #fff;
}
.confirm-button.danger:hover:not(:disabled) {
  filter: brightness(1.08);
}
.confirm-button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.confirm-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
