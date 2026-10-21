<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import {
  activeCardPlugins,
  activeListFilter_,
  activeNoteListFilters,
  activeNoteTools,
  ConfirmDialog,
  modifierLabel,
  onNoteTitleChange,
  request,
  setActiveListFilter,
  toast,
} from '@thisnote/plugin-api'
import { usePageStore } from '@/stores/notePage'
import NoteList from '@/components/NoteList.vue'
import NotePage from '@/components/page/NotePage.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { NoteDto, CardView } from '@/api/types'
import { copyImageToClipboard } from '@/components/page/clipboardImage'
import { useNoteTitle } from './useNoteTitle'

const route = useRoute()
const router = useRouter()
const nodes = useNoteStore()
const page = usePageStore()
let unsubscribeNoteTitleChange: (() => void) | null = null

const pageRef = ref<InstanceType<typeof NotePage> | null>(null)
const noteFontSize = ref(15)
const noteListRef = ref<InstanceType<typeof NoteList> | null>(null)
const notesForList = computed(() => {
  const onlyNote = typeof route.query.onlyNote === 'string' ? route.query.onlyNote : ''
  return onlyNote ? nodes.notesView.filter((note) => note.noteId === onlyNote) : nodes.notesView
})
/** 插件贡献的笔记工具（按插件启用状态过滤）。 */
const noteToolList = computed(() => activeNoteTools())
const titleNoteTools = computed(() =>
  noteToolList.value.filter((tool) => tool.placement !== 'below-title'),
)
const belowTitleNoteTools = computed(() =>
  noteToolList.value.filter((tool) => tool.placement === 'below-title'),
)
type NoteManagementFilter = ReturnType<typeof activeNoteListFilters>[number] & { hideNoteManagement?: boolean }
const allowNoteManagement = computed(() => {
  const active = activeListFilter_()
  if (!active) return true
  const filter = (activeNoteListFilters() as NoteManagementFilter[]).find(
    (item) => item.pluginId === active.pluginId && item.id === active.filterId,
  )
  return !filter?.hideNoteManagement
})

function applyFilterQuery() {
  const value = typeof route.query.filter === 'string' ? route.query.filter : ''
  if (!value) return
  const [pluginId, filterId] = value.split(':', 2)
  const registered = activeNoteListFilters().some((item) => item.pluginId === pluginId && item.id === filterId)
  if (!pluginId || !filterId || !registered) {
    console.warn('[notes] 忽略无效的列表筛选链接', { filter: value })
    return
  }
  setActiveListFilter(pluginId, filterId)
  console.info('[notes] 已应用列表筛选链接', { pluginId, filterId })
}
const {
  renaming,
  titleDraft,
  titleInput,
  titlePromptOpen,
  titlePromptDraft,
  titlePromptInput,
  titlePromptSaveButton,
  titlePromptBusy,
  onTitlePromptKeydown,
  saveTitleAndLeave,
  deleteUntitledNoteAndLeave,
  dismissTitlePrompt,
  applyRoute,
  startRename,
  cancelRename,
  commitTitle,
} = useNoteTitle(nodes, page, pageRef, allowNoteManagement)
const menu = ref<{ open: boolean; x: number; y: number; entries: MenuEntry[] }>({
  open: false,
  x: 0,
  y: 0,
  entries: [],
})
const confirmDialog = ref<{
  open: boolean
  title: string
  message: string
  confirmLabel: string
  action: (() => void | Promise<void>) | null
}>({ open: false, title: '', message: '', confirmLabel: '删除', action: null })
function openNode(id: string) {
  void router.push(`/notes/${id}`)
}

// ---------------------------------------------------------------- page actions

async function addCard() {
  if (!pageRef.value) return
  const created = await page.addBlock(
    { x: 40 + (page.cards.length + page.pendingCount) * 24, y: 40 + (page.cards.length + page.pendingCount) * 24 },
    {
      contentMd: '',
      surfaceWidth: pageRef.value.size.width / page.viewport.zoom,
      surfaceHeight: pageRef.value.size.height / page.viewport.zoom,
    },
  )
  if (created) await pageRef.value.beginEdit(created)
}

function askForConfirmation(
  title: string,
  message: string,
  action: () => void | Promise<void>,
  confirmLabel = '删除',
) {
  confirmDialog.value = { open: true, title, message, confirmLabel, action }
}

function closeConfirmation() {
  confirmDialog.value.open = false
  confirmDialog.value.action = null
}

async function confirmAction() {
  const action = confirmDialog.value.action
  closeConfirmation()
  if (action) await action()
}

function requestDeleteCard(view: CardView) {
  askForConfirmation(
    '删除卡片？',
    '卡片内容将被永久删除，笔记会保留。',
    async () => {
      await pageRef.value?.finishEditing(view.card.cardId)
      const deleted = await page.deleteBlock(view.card.cardId)
      const currentNote = nodes.current
      if (deleted && currentNote?.noteId === page.noteId) {
        currentNote.cardIds = (currentNote.cardIds ?? []).filter((id) => id !== view.card.cardId)
      }
    },
    '删除卡片',
  )
}

function requestDeleteNote(id: string) {
  if (!allowNoteManagement.value) return
  const note = nodes.nodes.find((item) => item.noteId === id)
  askForConfirmation(
    `彻底删除「${note?.title ?? '这篇笔记'}」？`,
    '卡片文件与笔记一起删除，无法恢复。',
    async () => {
      try {
        await nodes.deleteForever(id)
      } catch (e) {
        toast(`删除笔记失败：${(e as Error).message}`, 'error')
      }
    },
  )
}

function requestDeleteNotes(ids: string[]) {
  if (!allowNoteManagement.value || !ids.length) return
  askForConfirmation(
    `彻底删除选中的 ${ids.length} 篇笔记？`,
    '这些笔记的卡片文件会一起删除，无法恢复。',
    async () => {
      try {
        await nodes.deleteMany(ids)
        noteListRef.value?.clearSelection()
        if (nodes.currentId && ids.includes(nodes.currentId)) await router.push('/notes')
      } catch (e) {
        toast(`批量删除失败：${(e as Error).message}`, 'error')
      }
    },
    '删除',
  )
}

function onPageContext(p: { x: number; y: number; target: 'page' | 'card'; view?: CardView; imageSrc?: string }) {
  const entries: MenuEntry[] = []
  if (p.target === 'card' && p.view) {
    const view = p.view
    const cardId = view.card.cardId
    if (p.imageSrc) {
      const imageSrc = p.imageSrc
      entries.push({
        label: '复制图片',
        action: () => {
          void copyImageToClipboard(imageSrc)
            .then(() => toast('图片已复制', 'success'))
            .catch((e: unknown) => toast(`复制图片失败：${(e as Error).message}`, 'error'))
        },
      })
      entries.push({ separator: true })
    }
    const cardPlugins = activeCardPlugins()
    if (cardPlugins.length) {
      entries.push({ separator: true })
      for (const plugin of cardPlugins) {
        const enabled = plugin.isEnabled(cardId)
        entries.push({
          label: `${enabled ? '关闭' : '开启'}${plugin.title}`,
          action: () => {
            void plugin.toggle(cardId).catch((e) => toast((e as Error).message, 'error'))
          },
        })
      }
    }
    // 「编辑卡片内容」已移除，菜单可能为空或以分隔线开头，删除卡片前按需补分隔线
    if (entries.length) entries.push({ separator: true })
    entries.push(
      { label: '删除卡片', danger: true, action: () => requestDeleteCard(view) },
    )
  } else {
    entries.push(
      { label: '新建卡片（或双击页面空白处）', action: addCard },
      { separator: true },
      { label: '全选', shortcut: 'Ctrl A', action: () => page.selectAll() },
      { label: '适应内容', shortcut: 'Ctrl 1', action: () => pageRef.value && page.fit(pageRef.value.size) },
      { label: '重置视图', shortcut: 'Ctrl 0', action: () => page.resetView() },
      { separator: true },
      { label: page.snapToGrid ? '关闭网格吸附' : '开启网格吸附', action: () => page.toggleSnap() },
    )
  }
  menu.value = { open: true, x: p.x, y: p.y, entries }
}

// ---------------------------------------------------------------- node context menu

function onNodeContext(payload: { node: NoteDto; x: number; y: number }) {
  const entries: MenuEntry[] = []
  if (allowNoteManagement.value) {
    entries.push(
      { label: '修改标题', action: () => void noteListRef.value?.startRename(payload.node) },
      { separator: true },
      { label: '删除笔记', danger: true, action: () => requestDeleteNote(payload.node.noteId) },
    )
  }
  // 「打开」入口移除后，管理被隐藏（插件筛选）时不再有条目，此时不弹空菜单
  if (!entries.length) return
  menu.value = { open: true, x: payload.x, y: payload.y, entries }
}

// ---------------------------------------------------------------- shortcuts

async function createNoteFromShortcut() {
  const shortcut = `${modifierLabel()}+N`
  const bookId = nodes.activeBookId
  const created = await nodes.createNode({ bookId: bookId ?? undefined })
  if (!created) {
    console.warn('[notes] 快捷键新建笔记失败', { shortcut, bookId })
    return
  }
  console.info('[notes] 快捷键已新建笔记', { shortcut, noteId: created.noteId, bookId: created.bookId ?? null })
  await router.push(`/notes/${created.noteId}`)
}

function onKeydown(e: KeyboardEvent) {
  const primary = navigator.platform.toLowerCase().includes('mac') ? e.metaKey : e.ctrlKey
  const target = e.target as HTMLElement
  const typing = ['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable
  if (primary && !e.repeat && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'n' && allowNoteManagement.value) {
    e.preventDefault()
    void createNoteFromShortcut()
  }
  if (primary && e.key.toLowerCase() === 's' && !typing) {
    e.preventDefault()
    void pageRef.value?.finishEditing()
  }
  if (e.key === 'Escape') menu.value.open = false
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (!nodes.current || nodes.current.title.trim()) return
  event.preventDefault()
  event.returnValue = ''
}

async function loadNoteFontSize() {
  try {
    const settings = await request<{ fontSize?: number }>('GET', '/api/notes/settings/get')
    const fontSize = settings.fontSize
    noteFontSize.value = typeof fontSize === 'number' && Number.isInteger(fontSize) && fontSize >= 10 && fontSize <= 32 ? fontSize : 15
  } catch (e) {
    console.warn('读取笔记正文字号失败，使用默认值 15px', e)
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeunload', onBeforeUnload)
  unsubscribeNoteTitleChange = onNoteTitleChange((change) => {
    if (nodes.current?.noteId !== change.noteId) return
    if (renaming.value) cancelRename()
    page.noteTitle = change.title
  })
  applyFilterQuery()
  void applyRoute()
  void loadNoteFontSize()
  // 面板已在启动时加载列表；这里只在列表意外为空时兜底重拉一次。
  if (nodes.notesView.length === 0 && !nodes.loadingList) void nodes.refreshAll()
})
watch(() => route.query.filter, applyFilterQuery)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeunload', onBeforeUnload)
  unsubscribeNoteTitleChange?.()
  unsubscribeNoteTitleChange = null
})
</script>

<template>
  <div class="notes-view">
    <div class="view-body">
      <!-- The list stays put next to the page: opening a note must not take the list away,
           or getting back to a neighbour means going through the sidebar again. -->
      <div class="list-dock">
        <NoteList
          ref="noteListRef"
          :nodes="notesForList"
          :loading="nodes.loadingList"
          :current-id="nodes.currentId"
          :allow-note-management="allowNoteManagement"
          @open="openNode"
          @context="onNodeContext"
          @remove="requestDeleteNote"
          @remove-batch="requestDeleteNotes"
        />
      </div>

      <template v-if="nodes.current">
        <div class="page-column" :style="{ '--note-font-size': `${noteFontSize}px` }">
          <!-- The note's own header belongs to the page column, not to the whole view: it must
               sit beside the list rather than across the top of it. -->
          <header class="note-head">
            <div class="head-main">
              <input
                v-if="renaming && allowNoteManagement"
                ref="titleInput"
                v-model="titleDraft"
                class="title-input"
                placeholder="请输入标题"
                @keydown.enter.prevent="commitTitle"
                @keydown.esc.prevent.stop="cancelRename"
                @blur="commitTitle"
              />
              <h2
                v-else
                class="title"
                :class="{ placeholder: !nodes.current.title.trim() }"
                :title="allowNoteManagement ? (nodes.current.title.trim() ? '双击重命名' : '双击输入标题') : undefined"
                @dblclick="allowNoteManagement && startRename()"
              >
                {{ nodes.current.title || '请输入标题' }}
              </h2>
                              <!-- 实体插件贡献的笔记工具（收藏就是这一类）——核心不认识任何具体插件 -->
                <component
                  :is="tool.component"
                  v-for="tool in titleNoteTools"
                  :key="tool.pluginId"
                  :note-id="nodes.current.noteId"
                />
            </div>
            <div v-if="belowTitleNoteTools.length" class="note-tools-below-title">
              <component
                :is="tool.component"
                v-for="tool in belowTitleNoteTools"
                :key="tool.pluginId"
                :note-id="nodes.current.noteId"
              />
            </div>
          </header>

          <NotePage ref="pageRef" @context="onPageContext" />
        </div>
      </template>

      <div v-else class="empty">
        <h3>选择一篇笔记开始</h3>
        <p v-if="allowNoteManagement" class="small">
          或按 <span class="kbd">{{ modifierLabel() }}</span> <span class="kbd">N</span> 新建一篇。
        </p>
      </div>
    </div>

    <ContextMenu :open="menu.open" :x="menu.x" :y="menu.y" :entries="menu.entries" @close="menu.open = false" />
    <ConfirmDialog
      :open="confirmDialog.open"
      :title="confirmDialog.title"
      :message="confirmDialog.message"
      :confirm-label="confirmDialog.confirmLabel"
      @cancel="closeConfirmation"
      @confirm="confirmAction"
    />
    <Teleport to="body">
      <div v-if="titlePromptOpen" class="title-prompt-overlay" @click.self="dismissTitlePrompt">
        <section
          class="title-prompt pop-in"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="title-prompt-heading"
          aria-describedby="title-prompt-description"
          tabindex="-1"
          @keydown="onTitlePromptKeydown"
        >
          <div class="title-prompt-mark" aria-hidden="true">✎</div>
          <div class="title-prompt-copy">
            <h3 id="title-prompt-heading">这篇笔记还没有标题</h3>
            <p id="title-prompt-description">
              设置标题后即可离开；选择“取消并删除”会永久删除这篇笔记及其卡片。
            </p>
          </div>
          <label class="title-prompt-label" for="required-note-title">笔记标题</label>
          <input
            id="required-note-title"
            ref="titlePromptInput"
            v-model="titlePromptDraft"
            class="title-prompt-input"
            type="text"
            placeholder="请输入标题"
            :disabled="titlePromptBusy"
            @keydown.enter.stop.prevent="saveTitleAndLeave"
          />
          <div class="title-prompt-actions">
            <button
              type="button"
              class="title-prompt-button continue"
              :disabled="titlePromptBusy"
              @click="dismissTitlePrompt"
            >继续编辑</button>
            <button
              type="button"
              class="title-prompt-button delete"
              :disabled="titlePromptBusy"
              @click="deleteUntitledNoteAndLeave"
            >取消并删除</button>
            <button
              ref="titlePromptSaveButton"
              type="button"
              class="title-prompt-button save"
              :disabled="titlePromptBusy"
              @click="saveTitleAndLeave"
            >保存并离开</button>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.notes-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.note-head {
  flex: 0 0 auto;
  padding: var(--space-2) var(--space-4);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.head-main {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.note-tools-below-title {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 4px 8px;
  min-width: 0;
}
.title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  cursor: text;
}
.title.placeholder {
  color: var(--c-text-3);
  font-weight: 500;
}
.title-input {
  font-size: 17px;
  font-weight: 700;
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  padding: 2px 6px;
  outline: none;
  min-width: 260px;
}
.view-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.list-dock {
  flex: 0 0 auto;
  display: flex;
  min-height: 0;
}
.page-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.title-prompt-overlay {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 44%);
  backdrop-filter: blur(2px);
}
.title-prompt {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 8px 12px;
  box-sizing: border-box;
  width: min(460px, 100%);
  padding: 22px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-lg);
  outline: none;
}
.title-prompt-mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  font-size: 18px;
}
.title-prompt-copy h3 {
  margin: 2px 0 7px;
  font-size: 15px;
  font-weight: 700;
}
.title-prompt-copy p {
  margin: 0;
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.55;
}
.title-prompt-label,
.title-prompt-input,
.title-prompt-actions {
  grid-column: 1 / -1;
}
.title-prompt-label {
  margin-top: 5px;
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
}
.title-prompt-input {
  box-sizing: border-box;
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface-2);
  color: var(--c-text);
  font: inherit;
  outline: none;
}
.title-prompt-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 16%, transparent);
}
.title-prompt-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.title-prompt-button {
  min-height: 34px;
  padding: 7px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.title-prompt-button.continue {
  margin-right: auto;
  background: transparent;
  color: var(--c-text-2);
}
.title-prompt-button.delete {
  border-color: color-mix(in srgb, var(--danger) 42%, var(--c-border));
  background: color-mix(in srgb, var(--danger) 8%, var(--c-surface));
  color: var(--danger);
}
.title-prompt-button.save {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.title-prompt-button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.title-prompt-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
