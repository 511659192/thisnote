<script setup lang="ts">
import { ref } from 'vue'
import { host } from '@thisnote/plugin-api'
import type { EnabledMemoryCard } from '../api/memoryCycleApi'

defineProps<{ card: EnabledMemoryCard }>()
const emit = defineEmits<{ (e: 'preview'): void }>()

const { MarkdownView } = host()

/** 右键菜单位置（屏幕坐标）；null 表示未打开。 */
const menu = ref<{ x: number; y: number } | null>(null)

function openMenu(event: MouseEvent) {
  event.preventDefault()
  const width = 180
  const height = 70
  menu.value = {
    x: Math.min(event.clientX, window.innerWidth - width - 8),
    y: Math.min(event.clientY, window.innerHeight - height - 8),
  }
}

function closeMenu() {
  menu.value = null
}

function previewFromMenu() {
  closeMenu()
  emit('preview')
}
</script>

<template>
  <article
    class="rv-enabled-card"
    @click="emit('preview')"
    @contextmenu="openMenu"
  >
    <div class="rv-enabled-content">
      <component :is="MarkdownView" :content="card.contentMd" />
    </div>
  </article>

  <Teleport to="body">
    <div
      v-if="menu"
      class="rv-menu-backdrop"
      @click="closeMenu"
      @contextmenu.prevent="closeMenu"
    ></div>
    <div
      v-if="menu"
      class="rv-menu"
      role="menu"
      :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
    >
      <button type="button" class="rv-menu-item" role="menuitem" @click="previewFromMenu">打开预览</button>
      <p class="rv-menu-hint">左键单击卡片也可打开</p>
    </div>
  </Teleport>
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
.rv-enabled-content {
  overflow-x: hidden;
}
/* 已开启卡片按图片实际大小展示，不套用 MarkdownView 默认的 320px 高度上限 */
.rv-enabled-content :deep(.md-preview img) {
  max-height: none;
}
.rv-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 900;
}
.rv-menu {
  position: fixed;
  z-index: 901;
  min-width: 140px;
  padding: 4px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}
.rv-menu-item {
  display: block;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-text);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.rv-menu-item:hover {
  background: var(--c-surface-3);
}
.rv-menu-hint {
  margin: 4px 0 0;
  padding: 0 10px 4px;
  color: var(--c-text-3);
  font-size: 11px;
}
</style>
