<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

export interface MenuEntry {
  label?: string
  action?: () => void
  danger?: boolean
  separator?: boolean
  shortcut?: string
  disabled?: boolean
}

const props = defineProps<{
  open: boolean
  x: number
  y: number
  entries: MenuEntry[]
}>()
const emit = defineEmits<{ (e: 'close'): void }>()

const el = ref<HTMLElement | null>(null)
const pos = ref({ x: props.x, y: props.y })

function reposition() {
  const width = 210
  const height = props.entries.length * 28 + 12
  pos.value = {
    x: Math.min(props.x, window.innerWidth - width - 8),
    y: Math.min(props.y, window.innerHeight - height - 8),
  }
}

watch(
  () => [props.open, props.x, props.y],
  () => {
    if (props.open) reposition()
  },
)

function onDocClick(e: MouseEvent) {
  if (el.value && !el.value.contains(e.target as globalThis.Node)) emit('close')
}

onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  window.addEventListener('blur', () => emit('close'))
})
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClick))
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="el"
      class="ctx-menu pop-in"
      :style="{ left: `${pos.x}px`, top: `${pos.y}px` }"
    >
      <template v-for="(entry, i) in entries" :key="i">
        <div v-if="entry.separator" class="sep"></div>
        <button
          v-else
          class="item"
          :class="{ danger: entry.danger }"
          :disabled="entry.disabled"
          @click="
            () => {
              if (entry.disabled) return
              entry.action?.()
              emit('close')
            }
          "
        >
          <span>{{ entry.label }}</span>
          <span v-if="entry.shortcut" class="small muted">{{ entry.shortcut }}</span>
        </button>
      </template>
    </div>
  </Teleport>
</template>

<style scoped>
.ctx-menu {
  position: fixed;
  z-index: 150;
  min-width: 190px;
  padding: 5px;
  border-radius: var(--radius);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow-lg);
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  width: 100%;
  padding: 6px 9px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  text-align: left;
  color: var(--c-text-2);
}
.item:hover:not(:disabled) {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.item.danger {
  color: var(--danger);
}
.item:disabled {
  opacity: 0.4;
}
.sep {
  height: 1px;
  background: var(--c-border);
  margin: 4px 6px;
}
</style>
