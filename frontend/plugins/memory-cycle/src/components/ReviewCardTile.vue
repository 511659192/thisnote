<script setup lang="ts">
import { host } from '@thisnode/plugin-api'
import type { EnabledMemoryCard } from '../api/memoryCycleApi'

defineProps<{ card: EnabledMemoryCard }>()
const emit = defineEmits<{ (e: 'preview'): void }>()

const { MarkdownView } = host()
</script>

<template>
  <article class="rv-enabled-card" @click="emit('preview')">
    <div class="rv-enabled-content">
      <button
        class="btn sm ghost rv-preview-trigger"
        aria-label="预览卡片"
        title="预览卡片"
        @click.stop="emit('preview')"
      >
        <svg class="rv-preview-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      </button>
      <component :is="MarkdownView" :content="card.contentMd" />
    </div>
  </article>
</template>

<style scoped>
.rv-enabled-card {
  position: relative;
  display: inline-flex;
  box-sizing: border-box;
  width: 100%;
  max-height: 50vh;
  margin: 0 0 var(--space-3);
  break-inside: avoid;
  flex-direction: column;
  gap: var(--space-2);
  overflow: auto;
  padding: var(--space-3);
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-sm);
}
.rv-preview-trigger {
  position: absolute;
  top: var(--space-2);
  right: calc(var(--space-2) + 16px);
  z-index: 1;
}
.rv-preview-icon {
  display: block;
  width: 16px;
  height: 16px;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.rv-enabled-content {
  padding-right: 64px;
  overflow-x: hidden;
}
/* 已开启卡片按图片实际大小展示，不套用 MarkdownView 默认的 320px 高度上限 */
.rv-enabled-content :deep(.md-preview img) {
  max-height: none;
}
</style>
