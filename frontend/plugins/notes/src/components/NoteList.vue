<script setup lang="ts">
import { listHeaders_ } from '@thisnode/plugin-api'
import { useNoteStore } from '@/stores/notes'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/core/utils'
import type { NoteMeta } from '@/api/types'

defineProps<{ nodes: NoteMeta[]; loading?: boolean; currentId?: string | null }>()
const emit = defineEmits<{
  (e: 'open', id: string): void
  (e: 'context', payload: { node: NoteMeta; x: number; y: number }): void
  (e: 'remove', id: string): void
}>()

const store = useNoteStore()
const ui = useUiStore()
// 列表接口的 notebooks 只带归属 id（笔记 meta 里的字段），标题用这里已加载的台账解析。
const notebookTitle = (id: string) => store.notebooks.find((nb) => nb.id === id)?.title ?? id

/**
 * 空列表提示。列表只剩「全部笔记」一个视图，所以文案固定。
 */
const emptyState = '这里还没有笔记'

function onContext(e: MouseEvent, node: NoteMeta) {
  e.preventDefault()
  emit('context', { node, x: e.clientX, y: e.clientY })
}

/** 右上角的删除入口：硬删除不可恢复，弹一次确认（与侧栏删分组同一模式）。 */
function askRemove(node: NoteMeta) {
  if (!window.confirm(`彻底删除「${node.title}」？\n\n卡片文件与笔记一起删除，无法恢复。`)) return
  emit('remove', node.id)
}
</script>

<template>
  <div class="note-list">
    <!-- 列表页头部插槽：插件贡献的顶部工具（如标题搜索框）渲染在列表行上方 -->
    <div v-if="listHeaders_().length" class="list-headers">
      <template v-for="h in listHeaders_()" :key="h.pluginId">
        <component :is="h.component" />
      </template>
    </div>
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
          <span
            class="row-del"
            role="button"
            title="彻底删除"
            @click.stop="askRemove(n)"
          >×</span>
        </div>
        <!--
          归属分组：笔记必须有归属（默认是「收集箱」），后端只回 id，标题从侧栏台账解析。
        -->
        <div v-if="n.notebookId" class="row-2 groups">
          <span class="chip group-chip" title="所属分组">▣ {{ notebookTitle(n.notebookId) }}</span>
        </div>
        <div class="row-3">
          <span class="date small muted">{{ formatDate(n.updatedAt) }}</span>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.list-headers {
  flex: 0 0 auto;
  padding: 4px var(--space-2) 4px;
  border-bottom: 1px solid var(--c-border);
}

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
/* 右上角删除：悬停行时显现，点它不触发行打开 */
.row-del {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  line-height: 15px;
  text-align: center;
  border-radius: var(--radius);
  color: var(--c-text-3);
  font-size: 14px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
  user-select: none;
}
.note-row:hover .row-del {
  opacity: 0.6;
}
.note-row:hover .row-del:hover {
  opacity: 1;
  color: var(--danger);
  background: var(--c-surface-3);
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
.row-3 {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 1px;
  flex-wrap: wrap;
}
</style>
