<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  width: string
  height: string
  title?: string
  confirmLabel?: string
}>(), {
  title: '卡片尺寸',
  confirmLabel: '确定',
})

const emit = defineEmits<{
  (e: 'update:width', value: string): void
  (e: 'update:height', value: string): void
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

const widthInput = ref<HTMLInputElement | null>(null)
const error = ref('')

watch(() => props.open, async (open) => {
  if (!open) return
  error.value = ''
  await nextTick()
  widthInput.value?.focus()
  widthInput.value?.select()
})

function onWidth(event: Event) {
  error.value = ''
  emit('update:width', (event.target as HTMLInputElement).value)
}

function onHeight(event: Event) {
  error.value = ''
  emit('update:height', (event.target as HTMLInputElement).value)
}

function confirm() {
  const width = Number(props.width.trim())
  const height = Number(props.height.trim())
  if (!Number.isFinite(width) || width < 88 || !Number.isFinite(height) || height < 88) {
    error.value = '宽和高都不能小于 88'
    widthInput.value?.focus()
    return
  }
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="size-overlay" @click.self="emit('cancel')">
      <section
        class="size-dialog pop-in"
        role="dialog"
        aria-modal="true"
        aria-labelledby="custom-size-title"
        @keydown.esc.stop.prevent="emit('cancel')"
      >
        <h3 id="custom-size-title">{{ title }}</h3>
        <div class="size-fields">
          <label class="size-field">
            <span class="size-label">宽度（px）</span>
            <input
              ref="widthInput"
              class="size-input"
              type="number"
              inputmode="numeric"
              min="88"
              step="1"
              :value="width"
              @input="onWidth"
              @keydown.enter.stop.prevent="confirm"
            />
          </label>
          <label class="size-field">
            <span class="size-label">高度（px）</span>
            <input
              class="size-input"
              type="number"
              inputmode="numeric"
              min="88"
              step="1"
              :value="height"
              @input="onHeight"
              @keydown.enter.stop.prevent="confirm"
            />
          </label>
        </div>
        <p v-if="error" class="size-error">{{ error }}</p>
        <div class="size-actions">
          <button type="button" class="size-button cancel" @click="emit('cancel')">取消</button>
          <button type="button" class="size-button primary" @click="confirm">{{ confirmLabel }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.size-overlay {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 48%);
  backdrop-filter: blur(2px);
}
.size-dialog {
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;
  width: min(340px, 100%);
  padding: 20px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-lg);
}
.size-dialog h3 {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 700;
}
.size-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.size-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.size-label {
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
}
.size-input {
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
.size-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 16%, transparent);
}
.size-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
}
.size-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.size-button {
  min-width: 68px;
  padding: 7px 13px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.size-button.cancel {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}
.size-button.cancel:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.size-button.primary {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.size-button.primary:hover {
  filter: brightness(1.08);
}
.size-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
