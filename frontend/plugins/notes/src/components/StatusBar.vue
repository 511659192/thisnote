<script setup lang="ts">
import { computed } from 'vue'
import { useNoteStore } from '@/stores/notes'
import { usePageStore } from '@/stores/notePage'
import { backendMessage, backendOnline, shellState } from '@thisnote/plugin-api'

const nodes = useNoteStore()
const page = usePageStore()

const saveState = computed(() => page.savingContent ? '保存中…' : '已保存')
</script>

<template>
  <footer class="statusbar">
    <span class="dot" :class="backendOnline ? 'ok' : 'bad'" :title="backendMessage"></span>
    <span class="small muted">{{ backendOnline ? '本地后端已连接' : backendMessage }}</span>
    <span class="divider-v"></span>
    <template v-if="nodes.current">
      <span class="divider-v"></span>
      <span class="small muted">{{ saveState }}</span>
    </template>
    <div class="spacer"></div>
    <template v-if="nodes.current">
      <span class="small muted">选中 {{ page.selection.length }}</span>
      <span class="small muted">缩放 {{ Math.round(page.viewport.zoom * 100) }}%</span>
    </template>
    <span class="small muted">{{ shellState.theme === 'dark' ? '深色' : '浅色' }}</span>
  </footer>
</template>

<style scoped>
.statusbar {
  height: 26px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  background: var(--c-surface);
  border-top: 1px solid var(--c-border);
  font-size: 11.5px;
  user-select: none;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-text-3);
}
.dot.ok {
  background: var(--success);
}
.dot.bad {
  background: var(--danger);
}
.divider-v {
  width: 1px;
  height: 12px;
  background: var(--c-border);
}
</style>
