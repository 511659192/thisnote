<script setup lang="ts">
import type { Component } from 'vue'
import type { CardFormat } from './markdownFormat'

defineProps<{
  cardId: string
  collapsed: boolean
  formatMode: CardFormat
  detectedFormatLabel: string
  activePreset: string
  plugins: Array<{ id: string; cardComponent: Component }>
}>()

defineEmits<{
  (e: 'toggle-collapse'): void
  (e: 'select-format', mode: CardFormat): void
  (e: 'focus-format'): void
  (e: 'choose-fit-width', ratio: number | null): void
  (e: 'choose-custom-size'): void
}>()
</script>

<template>
  <div class="toolbar">
    <div class="card-tools">
      <template v-if="!collapsed">
        <component
          :is="plugin.cardComponent"
          v-for="plugin in plugins"
          :key="plugin.id"
          :card-id="cardId"
        />
      </template>
      <button
        class="collapse"
        :class="{ active: collapsed }"
        :title="collapsed ? '展开卡片' : '折叠卡片'"
        @pointerdown.stop
        @click.stop="$emit('toggle-collapse')"
      >
        {{ collapsed ? '▸' : '▾' }}
      </button>
    </div>

    <div class="format-control" @pointerdown.stop @click.stop="$emit('focus-format')">
      <button
        class="format-btn"
        :class="{ active: formatMode === 'auto' }"
        type="button"
        :title="'自动判定（当前：' + detectedFormatLabel + '）'"
        aria-label="格式：自动判定"
        @click="$emit('select-format', 'auto')"
      >A</button>
      <button
        class="format-btn"
        :class="{ active: formatMode === 'markdown' }"
        type="button"
        title="Markdown 格式"
        aria-label="格式：Markdown"
        @click="$emit('select-format', 'markdown')"
      >M</button>
      <button
        class="format-btn"
        :class="{ active: formatMode === 'text' }"
        type="button"
        title="普通文本格式"
        aria-label="格式：普通文本"
        @click="$emit('select-format', 'text')"
      >T</button>
    </div>

    <div v-if="!collapsed" class="fit-tools" @pointerdown.stop @click.stop>
      <button
        class="auto-fit"
        :class="{ active: activePreset === '1:1' }"
        title="1:1：宽度贴合内容"
        @click.stop="$emit('choose-fit-width', null)"
      >1:1</button>
      <button class="fit-pct" :class="{ active: activePreset === '25%' }" title="卡片宽度：可视宽度的 25%" @click.stop="$emit('choose-fit-width', 0.25)">25%</button>
      <button class="fit-pct" :class="{ active: activePreset === '50%' }" title="卡片宽度：可视宽度的 50%" @click.stop="$emit('choose-fit-width', 0.5)">50%</button>
      <button class="fit-pct" :class="{ active: activePreset === '75%' }" title="卡片宽度：可视宽度的 75%" @click.stop="$emit('choose-fit-width', 0.75)">75%</button>
      <button class="fit-pct" :class="{ active: activePreset === '100%' }" title="卡片宽度：顶满可视宽度（留出页面留白）" @click.stop="$emit('choose-fit-width', 1)">100%</button>
      <button class="fit-pct" :class="{ active: activePreset === 'custom' }" title="自定义尺寸（px）" @click.stop="$emit('choose-custom-size')">自定义</button>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: contents;
}
.format-control {
  position: absolute;
  top: 4px;
  left: 6px;
  z-index: 4;
  display: flex;
  gap: 2px;
}
.fit-tools {
  position: absolute;
  top: 4px;
  left: 76px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 2px;
}
.format-btn {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}
.format-btn:hover,
.format-btn.active {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.card-tools {
  position: absolute;
  top: 4px;
  right: 6px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 2px;
}
.collapse {
  opacity: 0;
  transition: opacity 0.12s;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  line-height: 1;
}
.collapse:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.collapse.active {
  color: var(--accent);
}
.auto-fit {
  height: 20px;
  padding: 0 4px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.auto-fit:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.fit-pct {
  height: 20px;
  padding: 0 5px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.fit-pct:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.auto-fit.active,
.fit-pct.active {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
</style>
