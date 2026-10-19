<script setup lang="ts">
import { computed } from 'vue'
import type { TagUsage } from '../tagStore'

const props = defineProps<{
  tags: TagUsage[]
}>()

const emit = defineEmits<{
  select: [tag: string]
}>()

const visibleTags = computed(() => props.tags.slice(0, 60))
const maxCount = computed(() => Math.max(1, ...visibleTags.value.map((tag) => tag.count)))

function bubbleSize(tag: TagUsage): number {
  const scale = Math.sqrt(tag.count / maxCount.value)
  return Math.round(56 + scale * 76)
}

function densityClass(tag: TagUsage): string {
  if (tag.noteCount >= 10) return 'density-5'
  if (tag.noteCount >= 6) return 'density-4'
  if (tag.noteCount >= 3) return 'density-3'
  if (tag.noteCount >= 2) return 'density-2'
  return 'density-1'
}
</script>

<template>
  <section v-if="visibleTags.length" class="bubble-panel">
    <header class="bubble-heading">
      <strong>标签分布</strong>
      <span>气泡越大出现越多，颜色越深覆盖笔记越多</span>
    </header>
    <div class="bubble-cloud">
      <button
        v-for="tag in visibleTags"
        :key="tag.label"
        type="button"
        class="bubble"
        :class="densityClass(tag)"
        :style="{ width: bubbleSize(tag) + 'px', height: bubbleSize(tag) + 'px' }"
        :title="`${tag.label}：${tag.count} 次出现，${tag.noteCount} 篇笔记`"
        :aria-label="`${tag.label}：${tag.count} 次出现，${tag.noteCount} 篇笔记`"
        @click="emit('select', tag.label)"
      >
        <span class="bubble-label">{{ tag.label }}</span>
        <span class="bubble-count">{{ tag.count }} 次</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.bubble-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 8px;
}

.bubble-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.bubble-heading strong {
  font-size: 14px;
}

.bubble-heading span {
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
}

.bubble-cloud {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 180px;
}

.bubble {
  display: grid;
  place-content: center;
  flex: 0 0 auto;
  gap: 1px;
  box-sizing: border-box;
  padding: 7px;
  overflow: hidden;
  color: var(--c-text);
  border: 1px solid transparent;
  border-radius: 50%;
  font: inherit;
  cursor: pointer;
  transition: transform 120ms ease, box-shadow 120ms ease;
}

.bubble:hover,
.bubble:focus-visible {
  z-index: 1;
  transform: scale(1.06);
  box-shadow: 0 5px 16px #0002;
  outline: none;
}

.bubble-label {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: 11px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.bubble-count {
  color: color-mix(in srgb, var(--c-text) 68%, transparent);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.density-1 {
  background: color-mix(in srgb, #4b9b87 24%, var(--c-surface));
  border-color: color-mix(in srgb, #4b9b87 48%, var(--c-border));
}

.density-2 {
  background: color-mix(in srgb, #5388c9 25%, var(--c-surface));
  border-color: color-mix(in srgb, #5388c9 50%, var(--c-border));
}

.density-3 {
  background: color-mix(in srgb, #7c71c9 26%, var(--c-surface));
  border-color: color-mix(in srgb, #7c71c9 52%, var(--c-border));
}

.density-4 {
  background: color-mix(in srgb, #c18a42 27%, var(--c-surface));
  border-color: color-mix(in srgb, #c18a42 54%, var(--c-border));
}

.density-5 {
  background: color-mix(in srgb, #c05f78 27%, var(--c-surface));
  border-color: color-mix(in srgb, #c05f78 54%, var(--c-border));
}

@media (prefers-reduced-motion: reduce) {
  .bubble {
    transition: none;
  }
}
</style>
