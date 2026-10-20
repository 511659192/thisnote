<script setup lang="ts">
import type { Component } from 'vue'
import type { CardView } from '@/api/types'

defineProps<{
  card: CardView
  markdownView: Component
  onWheel: (event: WheelEvent) => void
  onMouseDown: (event: MouseEvent) => void
}>()

defineEmits<{
  (e: 'close'): void
}>()
</script>

<template>
  <div
    class="card-preview-overlay"
    @click.self="$emit('close')"
    @dblclick.stop
    @pointerdown.stop
  >
    <section class="card-preview-dialog" role="dialog" aria-modal="true" aria-label="卡片预览">
      <header class="card-preview-header">
        <button class="btn sm ghost" aria-label="关闭预览" @click="$emit('close')">×</button>
      </header>
      <div
        class="card-preview-content"
        @wheel="onWheel"
        @mousedown="onMouseDown"
        @dragstart.prevent
      >
        <component :is="markdownView" :content="card.contentMd || ''" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.card-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  background: rgb(0 0 0 / 48%);
  overscroll-behavior: contain;
}
.card-preview-dialog {
  display: flex;
  width: fit-content;
  min-width: min(480px, 90vw);
  max-width: 90vw;
  max-height: 80vh;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}
.card-preview-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--c-border);
}
.card-preview-content {
  min-width: 0;
  max-height: calc(80vh - 56px);
  overflow: auto;
  padding: var(--space-4);
}
.card-preview-content :deep(.md-preview img) {
  max-width: none;
  max-height: none;
  cursor: grab;
  user-select: none;
}
.card-preview-content :deep(.md-preview img.preview-img-grabbing) {
  cursor: grabbing;
}
</style>
