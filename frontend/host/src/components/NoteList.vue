<script setup lang="ts">
import { computed } from 'vue'
import { useNoteStore } from '@/stores/notes'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/core/utils'
import type { NoteMeta } from '@/api/types'

defineProps<{ nodes: NoteMeta[]; loading?: boolean; currentId?: string | null }>()
const emit = defineEmits<{
  (e: 'open', id: string): void
  (e: 'context', payload: { node: NoteMeta; x: number; y: number }): void
}>()

const store = useNoteStore()
const ui = useUiStore()
// 列表接口的 notebooks 只带归属 id（笔记 meta 里的字段），标题用这里已加载的台账解析。
const notebookTitle = (id: string) => store.notebooks.find((nb) => nb.id === id)?.title ?? id

/**
 * 空列表要说清「空的是哪个视图」。
 *
 * <p>原先无论当前是回收站还是归档，一律显示「这里还没有笔记」这种不带位置的提示——在回收站里
 * 让人分不清自己在哪。回收站当前没有界面入口（命令面板已移除），这个提示就成了唯一的位置线索，必须准确。
 */
const emptyState = computed(() => {
  switch (store.filter) {
    case 'trash':
      return '回收站是空的'
    case 'daily':
      return '还没有每日笔记'
    case 'template':
      return '还没有模板'
    case 'recent':
      return '最近没有编辑过笔记'
    default:
      return '这里还没有笔记'
  }
})

function onContext(e: MouseEvent, node: NoteMeta) {
  e.preventDefault()
  emit('context', { node, x: e.clientX, y: e.clientY })
}
</script>

<template>
  <div class="note-list">
    <div class="list-body">
      <div v-if="loading" class="list-loading small muted">加载中…</div>
      <div v-else-if="!nodes.length" class="list-empty">
        <p class="small muted">{{ emptyState }}</p>
      </div>

      <button
        v-for="n in nodes"
        :key="n.id"
        class="note-row"
        :class="{ active: n.id === currentId }"
        :data-node-id="n.id"
        draggable="true"
        title="拖到侧栏分组里可归类"
        @click="emit('open', n.id)"
        @contextmenu="onContext($event, n)"
        @dragstart="
          (e: DragEvent) => {
            e.dataTransfer?.setData('application/x-thisnode-node', n.id)
            e.dataTransfer?.setData('text/plain', n.id)
            if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy'
          }
        "
      >
        <div class="row-1">
          <span class="title">{{ n.title }}</span>
        </div>
        <!--
          归属分组：笔记必须有归属（默认是「收集箱」），后端只回 id，标题从侧栏台账解析。
        -->
        <div v-if="n.notebookId" class="row-2 groups">
          <span class="chip group-chip" title="所属分组">▣ {{ notebookTitle(n.notebookId) }}</span>
        </div>
        <div v-if="n.summary" class="preview">{{ n.summary }}</div>
        <div class="row-3">
          <span class="date small muted">{{ formatDate(n.updatedAt) }}</span>
          <span v-if="n.inboundCount || n.outboundCount" class="small muted links">
            ⇄ {{ n.inboundCount }}/{{ n.outboundCount }}
          </span>
          <span v-for="t in n.tags.slice(0, 2)" :key="t" class="chip">#{{ t }}</span>
          <span v-if="n.tags.length > 2" class="small muted">+{{ n.tags.length - 2 }}</span>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.note-list {
  width: 296px;
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--c-border);
  background: var(--c-surface-2);
  min-height: 0;
}
.list-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-1);
  min-height: 0;
}
.list-loading,
.list-empty {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  align-items: center;
  padding: var(--space-5) var(--space-3);
  text-align: center;
}

.note-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  padding: 8px 10px;
  border-radius: var(--radius);
  text-align: left;
  border: 1px solid transparent;
}
.note-row:hover {  background: var(--c-surface-3);
}
.note-row.active {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 30%, transparent);
}
.row-1 {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}
.row-1 .title {
  flex: 1;
  font-size: 13.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-1 .fav {
  font-size: 11px;
  opacity: 0.7;
}
/* 归属分组那行：比标签更淡，它是「位置」不是「属性」 */
.groups {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  overflow: hidden;
}
.group-chip {
  font-size: 10.5px;
  padding: 0 5px;
  opacity: 0.85;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fav {
  color: var(--warning);
}
.preview {
  font-size: 12px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.5;
}
.row-3 {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 1px;
  flex-wrap: wrap;
}
.links {
  font-family: var(--font-mono);
  font-size: 10.5px;
}
</style>
