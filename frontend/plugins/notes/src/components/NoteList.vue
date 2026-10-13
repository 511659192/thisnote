<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { listHeaders_, toast } from '@thisnode/plugin-api'
import { noteApi } from '@/api/note'
import { useNoteStore } from '@/stores/notes'
import { usePageStore } from '@/stores/notePage'
import type { NoteMeta } from '@/api/types'

const props = withDefaults(defineProps<{
  nodes: NoteMeta[]
  loading?: boolean
  currentId?: string | null
  allowNoteManagement?: boolean
}>(), { allowNoteManagement: true })
type NoteManagementHeader = ReturnType<typeof listHeaders_>[number] & { noteManagement?: boolean }
const visibleListHeaders = computed(() =>
  (listHeaders_() as NoteManagementHeader[]).filter(
    (header) => props.allowNoteManagement || !header.noteManagement,
  ),
)
const emit = defineEmits<{
  (e: 'open', id: string): void
  (e: 'context', payload: { node: NoteMeta; x: number; y: number }): void
  (e: 'remove', id: string): void
}>()

const store = useNoteStore()
const page = usePageStore()
const renamingId = ref<string | null>(null)
const titleDraft = ref('')
const titleInput = ref<HTMLInputElement | null>(null)
let titleOpenTimer: number | null = null
let savingNoteId: string | null = null

function clearTitleOpenTimer() {
  if (titleOpenTimer === null) return
  window.clearTimeout(titleOpenTimer)
  titleOpenTimer = null
}

function openNote(noteId: string) {
  clearTitleOpenTimer()
  emit('open', noteId)
}

function onTitleClick(event: MouseEvent, note: NoteMeta) {
  event.stopPropagation()
  if (event.detail > 1) {
    clearTitleOpenTimer()
    return
  }
  clearTitleOpenTimer()
  titleOpenTimer = window.setTimeout(() => {
    titleOpenTimer = null
    emit('open', note.noteId)
  }, 250)
}

function onTitleDoubleClick(event: MouseEvent, note: NoteMeta) {
  event.stopPropagation()
  event.preventDefault()
  if (props.allowNoteManagement) {
    void startRename(note)
    return
  }
  clearTitleOpenTimer()
  emit('open', note.noteId)
}

async function startRename(note: NoteMeta) {
  if (!props.allowNoteManagement) return
  clearTitleOpenTimer()
  renamingId.value = note.noteId
  titleDraft.value = note.title
  await nextTick()
  titleInput.value?.focus()
  titleInput.value?.select()
}

defineExpose({ startRename })

function cancelRename(noteId: string) {
  if (savingNoteId === noteId) return
  renamingId.value = null
  titleDraft.value = ''
}

async function commitRename(note: NoteMeta) {
  if (!props.allowNoteManagement) {
    renamingId.value = null
    titleDraft.value = ''
    return
  }
  if (renamingId.value !== note.noteId || savingNoteId === note.noteId) return
  const title = titleDraft.value.trim()
  if (!title) {
    toast('笔记标题不能为空', 'error')
    await nextTick()
    titleInput.value?.focus()
    return
  }
  if (title === note.title) {
    renamingId.value = null
    return
  }

  savingNoteId = note.noteId
  try {
    const updated = await noteApi.update(note.noteId, { title })
    note.title = updated.title
    if (store.current?.noteId === note.noteId) store.current.title = updated.title
    if (page.noteId === note.noteId) page.noteTitle = updated.title
    renamingId.value = null
    await store.loadList()
  } catch (e) {
    toast(`重命名失败：${(e as Error).message}`, 'error')
    await nextTick()
    titleInput.value?.focus()
    titleInput.value?.select()
  } finally {
    savingNoteId = null
  }
}

onBeforeUnmount(clearTitleOpenTimer)

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

/** Ask the parent to confirm before permanently deleting the note and its cards. */
function askRemove(node: NoteMeta) {
  if (!props.allowNoteManagement) return
  emit('remove', node.noteId)
}
</script>

<template>
  <div class="note-list">
    <!-- 列表页头部插槽：插件贡献的顶部工具（如标题搜索框）渲染在列表行上方 -->
    <div v-if="visibleListHeaders.length" class="list-headers">
      <template v-for="h in visibleListHeaders" :key="h.pluginId">
        <component :is="h.component" />
      </template>
    </div>
    <div class="list-body">
      <div v-if="loading" class="list-loading small muted">加载中…</div>
      <div v-else-if="!nodes.length" class="list-empty">
        <p class="small muted">{{ emptyState }}</p>
      </div>

      <div
        v-for="n in nodes"
        :key="n.noteId"
        class="note-row"
        :class="{ active: n.noteId === currentId }"
        :data-node-id="n.noteId"
        role="button"
        tabindex="0"
        @click="openNote(n.noteId)"
        @keydown.enter.self.prevent="openNote(n.noteId)"
        @contextmenu="onContext($event, n)"
      >
        <div class="row-1">
          <input
            v-if="renamingId === n.noteId && allowNoteManagement"
            ref="titleInput"
            v-model="titleDraft"
            class="title title-edit"
            aria-label="笔记标题"
            @click.stop
            @dblclick.stop
            @keydown.enter.stop.prevent="commitRename(n)"
            @keydown.esc.stop.prevent="cancelRename(n.noteId)"
            @blur="commitRename(n)"
          />
          <span
            v-else
            class="title"
            :class="{ placeholder: !n.title.trim() }"
            :title="allowNoteManagement ? (n.title.trim() ? '双击重命名' : '双击输入标题') : undefined"
            @click="onTitleClick($event, n)"
            @dblclick="onTitleDoubleClick($event, n)"
          >{{ n.title.trim() ? n.title : '请输入标题' }}</span>
          <span
            v-if="allowNoteManagement"
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
      </div>
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
  color: var(--c-text-2);
  cursor: pointer;
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
  min-width: 0;
  font-size: 13.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-1 .title.placeholder {
  color: var(--c-text-3);
  font-weight: 400;
}
.row-1 .title-edit {
  padding: 1px 2px;
  border: 0;
  border-bottom: 1px solid var(--accent);
  background: transparent;
  color: inherit;
  font: inherit;
  outline: none;
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
</style>
