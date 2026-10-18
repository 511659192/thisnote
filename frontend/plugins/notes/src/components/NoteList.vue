<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { activeNoteBatchActions, activeNoteRowActions, listHeaders_, toast, type NoteBatchAction, type NoteRef, type NoteRowAction } from '@thisnode/plugin-api'
import { noteApi } from '@/api/note'
import { useNoteStore } from '@/stores/notes'
import { usePageStore } from '@/stores/notePage'
import type { NoteDto } from '@/api/types'

const props = withDefaults(defineProps<{
  nodes: NoteDto[]
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
  (e: 'context', payload: { node: NoteDto; x: number; y: number }): void
  (e: 'remove', id: string): void
  (e: 'remove-batch', ids: string[]): void
}>()

const store = useNoteStore()
const page = usePageStore()
const renamingId = ref<string | null>(null)
const titleDraft = ref('')
const titleInput = ref<HTMLInputElement | null>(null)
const selecting = computed(() => store.batchSelecting)
const selected = computed(() => store.batchSelected)
const moveMenuOpen = ref(false)
let titleOpenTimer: number | null = null
let savingNoteId: string | null = null

const allSelected = computed(() => props.nodes.length > 0 && selected.value.length === props.nodes.length)

function stopSelecting() {
  store.stopBatchSelect()
  moveMenuOpen.value = false
}

function clearSelection() {
  stopSelecting()
}

function toggleSelected(noteId: string) {
  store.toggleBatchSelected(noteId)
}

function toggleAll() {
  store.toggleBatchAll(props.nodes.map((note) => note.noteId))
}

function requestBatchRemove() {
  if (!selected.value.length) return
  emit('remove-batch', [...selected.value])
}

async function moveSelected(bookId: string) {
  if (!selected.value.length) return
  try {
    await store.moveMany([...selected.value], bookId)
    const book = store.books.find((item) => item.id === bookId)
    toast(`已转入「${book?.title ?? bookId}」`, 'success')
    stopSelecting()
  } catch (e) {
    toast(`转入失败：${(e as Error).message}`, 'error')
  }
}

function clearTitleOpenTimer() {
  if (titleOpenTimer === null) return
  window.clearTimeout(titleOpenTimer)
  titleOpenTimer = null
}

function openNote(noteId: string) {
  clearTitleOpenTimer()
  emit('open', noteId)
}

/** 把笔记拖到侧栏的分组上即可转入该分组。 */
function onDragStart(event: DragEvent, note: NoteDto) {
  if (!props.allowNoteManagement || selecting.value) {
    event.preventDefault()
    return
  }
  if (!event.dataTransfer) return
  event.dataTransfer.setData('application/x-thisnote-note', note.noteId)
  event.dataTransfer.setData('text/plain', note.title)
  event.dataTransfer.effectAllowed = 'move'
}

function onTitleClick(event: MouseEvent, note: NoteDto) {
  event.stopPropagation()
  if (selecting.value) {
    toggleSelected(note.noteId)
    return
  }
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

function onTitleDoubleClick(event: MouseEvent, note: NoteDto) {
  event.stopPropagation()
  event.preventDefault()
  if (selecting.value) return
  if (props.allowNoteManagement) {
    void startRename(note)
    return
  }
  clearTitleOpenTimer()
  emit('open', note.noteId)
}

async function startRename(note: NoteDto) {
  if (!props.allowNoteManagement) return
  clearTitleOpenTimer()
  renamingId.value = note.noteId
  titleDraft.value = note.title
  await nextTick()
  titleInput.value?.focus()
  titleInput.value?.select()
}

defineExpose({ startRename, clearSelection })

function cancelRename(noteId: string) {
  if (savingNoteId === noteId) return
  renamingId.value = null
  titleDraft.value = ''
}

async function commitRename(note: NoteDto) {
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
const bookTitle = (id: string) => store.books.find((nb) => nb.id === id)?.title ?? id

/**
 * 空列表提示。列表只剩「全部笔记」一个视图，所以文案固定。
 */
const emptyState = '这里还没有笔记'

function onContext(e: MouseEvent, node: NoteDto) {
  e.preventDefault()
  emit('context', { node, x: e.clientX, y: e.clientY })
}

/** Ask the parent to confirm before permanently deleting the note and its cards. */
function askRemove(node: NoteDto) {
  if (!props.allowNoteManagement) return
  emit('remove', node.noteId)
}

// ---------------------------------------------------------------- 插件动作（收藏 / 记忆曲线等）

/** 行内图标与批量按钮都来自插件注册表；核心不认识任何具体插件。 */
const rowActions = computed(() => activeNoteRowActions())
const batchActions = computed(() => activeNoteBatchActions())

function noteRef(note: NoteDto): NoteRef {
  return { noteId: note.noteId, title: note.title, cardIds: note.cardIds ?? [] }
}

async function runRowAction(action: NoteRowAction, note: NoteDto) {
  if (!action.toggle) return
  try {
    await action.toggle(noteRef(note))
  } catch (e) {
    const label = action.title?.(noteRef(note)) ?? '操作'
    toast(`${label}失败：${(e as Error).message}`, 'error')
  }
}

async function runBatchAction(action: NoteBatchAction) {
  const ids = new Set(selected.value)
  const notes = props.nodes.filter((note) => ids.has(note.noteId)).map(noteRef)
  if (!notes.length) return
  try {
    const message = await action.run(notes)
    toast(message || `${action.label}：${notes.length} 篇笔记`, 'success')
  } catch (e) {
    toast(`${action.label}失败：${(e as Error).message}`, 'error')
  }
}

function loadActionStates() {
  const notes = props.nodes.map(noteRef)
  for (const action of rowActions.value) {
    const pending = action.loadState?.(notes)
    if (pending) void pending.catch(() => undefined)
  }
}

onMounted(loadActionStates)
watch(() => props.nodes, loadActionStates)
</script>

<template>
  <div class="note-list">
    <!-- 列表页头部插槽：插件贡献的顶部工具（如标题搜索框）渲染在列表行上方 -->
    <div v-if="visibleListHeaders.length" class="list-headers">
      <template v-for="h in visibleListHeaders" :key="h.pluginId">
        <component :is="h.component" />
      </template>
    </div>
    <div v-if="allowNoteManagement && selecting" class="list-tools">
      <button type="button" class="tool-btn" @click="toggleAll">{{ allSelected ? '取消全选' : '全选' }}</button>
      <button type="button" class="tool-btn" :disabled="!selected.length" @click="moveMenuOpen = !moveMenuOpen">
        转入笔记本
      </button>
      <button
        v-for="action in batchActions"
        :key="action.pluginId + action.label"
        type="button"
        class="tool-btn"
        :disabled="!selected.length"
        :title="action.title"
        @click="runBatchAction(action)"
      >{{ action.label }}</button>
      <button type="button" class="tool-btn danger" :disabled="!selected.length" @click="requestBatchRemove">
        删除选中{{ selected.length ? `（${selected.length}）` : '' }}
      </button>
      <button type="button" class="tool-btn" @click="stopSelecting">关闭</button>
    </div>
    <div v-if="selecting && moveMenuOpen" class="book-menu">
      <button
        v-for="book in store.books"
        :key="book.id"
        type="button"
        class="tool-btn"
        @click="moveSelected(book.id)"
      >{{ book.title }}</button>
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
        :class="{ active: n.noteId === currentId, selected: selected.includes(n.noteId) }"
        :data-node-id="n.noteId"
        :draggable="allowNoteManagement && !selecting"
        role="button"
        tabindex="0"
        @click="selecting ? toggleSelected(n.noteId) : openNote(n.noteId)"
        @keydown.enter.self.prevent="selecting ? toggleSelected(n.noteId) : openNote(n.noteId)"
        @contextmenu="onContext($event, n)"
        @dragstart="onDragStart($event, n)"
      >
        <div class="row-1">
          <input
            v-if="selecting"
            type="checkbox"
            class="row-check"
            :checked="selected.includes(n.noteId)"
            aria-label="选择笔记"
            @click.stop="toggleSelected(n.noteId)"
          />
          <input
            v-if="renamingId === n.noteId && allowNoteManagement && !selecting"
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
            v-if="allowNoteManagement && !selecting"
            class="row-del"
            role="button"
            title="彻底删除"
            @click.stop="askRemove(n)"
          >×</span>
        </div>
        <!--
          归属分组：笔记必须有归属（默认是「收集箱」），后端只回 id，标题从侧栏台账解析。
        -->
        <div v-if="n.bookId" class="row-2 groups">
          <span class="chip group-chip" title="所属分组">▣ {{ bookTitle(n.bookId) }}</span>
          <span v-if="allowNoteManagement" class="row-actions">
            <template v-for="action in rowActions" :key="action.pluginId">
              <component :is="action.component" v-if="action.component" :note="noteRef(n)" />
              <span
                v-else
                class="row-action"
                :class="{ active: action.isActive?.(noteRef(n)) }"
                role="button"
                :title="action.title?.(noteRef(n))"
                @click.stop="runRowAction(action, n)"
              >{{ action.isActive?.(noteRef(n)) && action.activeGlyph ? action.activeGlyph : action.glyph }}</span>
            </template>
          </span>
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

.list-tools {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px var(--space-2);
  border-bottom: 1px solid var(--c-border);
}

.book-menu {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px var(--space-2);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface-2);
}

.tool-btn {
  padding: 2px 8px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-text-2);
  font: inherit;
  font-size: 11.5px;
  cursor: pointer;
}

.tool-btn:hover {
  background: var(--c-surface-3);
}

.tool-btn.danger {
  color: var(--danger);
}

.tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.row-check {
  flex: 0 0 auto;
  margin: 0;
  accent-color: var(--accent);
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
.note-row.selected {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
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

/* 行内快捷操作（收藏 / 记忆曲线）：常驻显示，位于标题行右侧 */
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  flex: 0 0 auto;
}
.row-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: var(--radius-sm);
  color: var(--c-text-2);
  font-size: 16px;
  line-height: 1;
  opacity: 0.5;
  cursor: pointer;
  user-select: none;
}
.row-action:hover {
  background: var(--c-surface-3);
  color: var(--accent);
  opacity: 1;
}
.row-action.active {
  /* 收藏用实心/空心区分状态，不再用金色强调 */
  color: var(--c-text-2);
  opacity: 1;
}
</style>