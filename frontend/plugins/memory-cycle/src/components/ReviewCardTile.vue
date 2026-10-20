<script setup lang="ts">
import { ref } from 'vue'
import { host } from '@thisnote/plugin-api'
import type { EnabledMemoryCard } from '../api/memoryCycleApi'
import ReviewContextMenu from './ReviewContextMenu.vue'

defineProps<{ card: EnabledMemoryCard }>()
const emit = defineEmits<{
  (e: 'preview'): void
  (e: 'locate'): void
  (e: 'delete'): void
}>()

const { MarkdownView } = host()

/** 右键菜单位置（屏幕坐标）；null 表示未打开。 */
const menu = ref<{ x: number; y: number } | null>(null)

function openMenu(event: MouseEvent) {
  event.preventDefault()
  const width = 180
  const height = 105
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

function deleteFromMenu() {
  closeMenu()
  emit('delete')
}

function locateFromMenu() {
  closeMenu()
  emit('locate')
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

  <ReviewContextMenu
    :open="menu !== null"
    :x="menu?.x ?? 0"
    :y="menu?.y ?? 0"
    can-preview
    @preview="previewFromMenu"
    @locate="locateFromMenu"
    @delete="deleteFromMenu"
    @close="closeMenu"
  />
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
</style>
