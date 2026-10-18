<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  modelValue: string
  label?: string
  placeholder?: string
  confirmLabel?: string
}>(), {
  label: '名称',
  placeholder: '',
  confirmLabel: '确定',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

const input = ref<HTMLInputElement | null>(null)
const error = ref('')

watch(() => props.open, async (open) => {
  if (!open) return
  error.value = ''
  await nextTick()
  input.value?.focus()
  input.value?.select()
})

function onInput(event: Event) {
  error.value = ''
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function confirm() {
  if (!props.modelValue.trim()) {
    error.value = '名称不能为空'
    input.value?.focus()
    return
  }
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="prompt-overlay" @click.self="emit('cancel')">
      <section
        class="prompt-dialog pop-in"
        role="dialog"
        aria-modal="true"
        aria-labelledby="prompt-dialog-title"
        @keydown.esc.stop.prevent="emit('cancel')"
      >
        <h3 id="prompt-dialog-title">{{ title }}</h3>
        <label class="prompt-label" for="prompt-dialog-input">{{ label }}</label>
        <input
          id="prompt-dialog-input"
          ref="input"
          class="prompt-input"
          type="text"
          :value="modelValue"
          :placeholder="placeholder"
          @input="onInput"
          @keydown.enter.stop.prevent="confirm"
        />
        <p v-if="error" class="prompt-error">{{ error }}</p>
        <div class="prompt-actions">
          <button type="button" class="prompt-button cancel" @click="emit('cancel')">取消</button>
          <button type="button" class="prompt-button primary" @click="confirm">{{ confirmLabel }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.prompt-overlay {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 48%);
  backdrop-filter: blur(2px);
}
.prompt-dialog {
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;
  width: min(320px, 100%);
  padding: 20px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-lg);
}
.prompt-dialog h3 {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 700;
}
.prompt-label {
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
}
.prompt-input {
  box-sizing: border-box;
  width: 100%;
  padding: 8px 11px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface-2);
  color: var(--c-text);
  font: inherit;
  outline: none;
}
.prompt-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 16%, transparent);
}
.prompt-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
}
.prompt-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.prompt-button {
  min-width: 68px;
  padding: 7px 13px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.prompt-button.cancel {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}
.prompt-button.cancel:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.prompt-button.primary {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.prompt-button.primary:hover {
  filter: brightness(1.08);
}
.prompt-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
