<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  modelValue: string
  title?: string
  label?: string
  confirmLabel?: string
}>(), {
  title: '卡片宽度',
  label: '宽度（px）',
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
  const value = Number(props.modelValue.trim())
  if (!Number.isFinite(value) || value < 88) {
    error.value = '请输入不小于 88 的数值'
    input.value?.focus()
    return
  }
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="width-overlay" @click.self="emit('cancel')">
      <section
        class="width-dialog pop-in"
        role="dialog"
        aria-modal="true"
        aria-labelledby="custom-width-title"
        @keydown.esc.stop.prevent="emit('cancel')"
      >
        <h3 id="custom-width-title">{{ title }}</h3>
        <label class="width-label" for="custom-card-width">{{ label }}</label>
        <input
          id="custom-card-width"
          ref="input"
          class="width-input"
          type="number"
          inputmode="numeric"
          min="88"
          step="1"
          :value="modelValue"
          @input="onInput"
          @keydown.enter.stop.prevent="confirm"
        />
        <p v-if="error" class="width-error">{{ error }}</p>
        <div class="width-actions">
          <button type="button" class="width-button cancel" @click="emit('cancel')">取消</button>
          <button type="button" class="width-button primary" @click="confirm">{{ confirmLabel }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.width-overlay {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 48%);
  backdrop-filter: blur(2px);
}
.width-dialog {
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
.width-dialog h3 {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 700;
}
.width-label {
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
}
.width-input {
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
.width-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 16%, transparent);
}
.width-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
}
.width-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.width-button {
  min-width: 68px;
  padding: 7px 13px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.width-button.cancel {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}
.width-button.cancel:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.width-button.primary {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.width-button.primary:hover {
  filter: brightness(1.08);
}
.width-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
