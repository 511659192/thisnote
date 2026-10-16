<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import {
  activeCardPlugins,
  activeListFilter_,
  activeNoteListFilters,
  activeNoteTools,
  primaryLabel,
  toast,
} from '@thisnode/plugin-api'
import { usePageStore } from '@/stores/notePage'
import NoteList from '@/components/NoteList.vue'
import NotePage from '@/components/page/NotePage.vue'
import StatusBar from '@/components/StatusBar.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import type { NoteMeta, CardView } from '@/api/types'
import { copyImageToClipboard } from '@/components/page/clipboardImage'

const route = useRoute()
const router = useRouter()
const nodes = useNoteStore()
const page = usePageStore()

const pageRef = ref<InstanceType<typeof NotePage> | null>(null)
const noteListRef = ref<InstanceType<typeof NoteList> | null>(null)
/** 插件贡献的笔记工具（按插件启用状态过滤）。 */
const noteToolList = computed(() => activeNoteTools())
type NoteManagementFilter = ReturnType<typeof activeNoteListFilters>[number] & { hideNoteManagement?: boolean }
const allowNoteManagement = computed(() => {
  const active = activeListFilter_()
  if (!active) return true
  const filter = (activeNoteListFilters() as NoteManagementFilter[]).find(
    (item) => item.pluginId === active.pluginId && item.id === active.filterId,
  )
  return !filter?.hideNoteManagement
})
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
const renaming = ref(false)
/** 列表面板是否收起到左缘；默认不收缩。 */
const listCollapsed = ref(false)
const titleDraft = ref('')
let titleDraftNoteId: string | null = null
const titleInput = ref<HTMLInputElement | null>(null)
const titlePromptOpen = ref(false)
const titlePromptDraft = ref('')
const titlePromptInput = ref<HTMLInputElement | null>(null)
const titlePromptSaveButton = ref<HTMLButtonElement | null>(null)
const titlePromptBusy = ref(false)
let titlePromptNoteId: string | null = null
let titlePromptPromise: Promise<boolean> | null = null
let resolveTitlePrompt: ((canLeave: boolean) => void) | null = null
/**
 * Set by Escape so the blur that follows cannot persist the abandoned draft.
 *
 * Unmounting the input (which is how the rename ends) fires `blur`, and `commitTitle` used to
 * run from that blur regardless of *why* the field closed — so Escape saved the edit instead of
 * discarding it. The flag makes "cancel" reach the blur handler.
 */
let renameCancelled = false
let pendingTitleCommit: Promise<NoteMeta | null> | null = null
function openNode(id: string) {
  void router.push(`/notes/${id}`)
}

async function saveTitle(title: string): Promise<NoteMeta | null> {
  const pending = nodes.updateCurrent({ title })
  pendingTitleCommit = pending
  try {
    return await pending
  } finally {
    if (pendingTitleCommit === pending) pendingTitleCommit = null
  }
}

function requestTitlePrompt(note: NoteMeta): Promise<boolean> {
  if (titlePromptPromise) return titlePromptPromise

  titlePromptNoteId = note.noteId
  titlePromptDraft.value = titleDraftNoteId === note.noteId ? titleDraft.value : note.title
  titlePromptBusy.value = false
  titlePromptOpen.value = true
  const pending = new Promise<boolean>((resolve) => {
    resolveTitlePrompt = resolve
  })
  titlePromptPromise = pending
  void nextTick(() => titlePromptInput.value?.focus())
  return pending
}

function finishTitlePrompt(canLeave: boolean) {
  const resolve = resolveTitlePrompt
  resolveTitlePrompt = null
  titlePromptPromise = null
  titlePromptNoteId = null
  titlePromptOpen.value = false
  titlePromptBusy.value = false
  resolve?.(canLeave)
}

function dismissTitlePrompt() {
  if (titlePromptBusy.value) return
  finishTitlePrompt(false)
}

function onTitlePromptKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    dismissTitlePrompt()
    return
  }
  if (event.key !== 'Tab') return

  const first = titlePromptInput.value
  const last = titlePromptSaveButton.value
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

async function saveTitleAndLeave() {
  if (titlePromptBusy.value) return
  const title = titlePromptDraft.value.trim()
  if (!title) {
    toast('笔记标题不能为空', 'error')
    titlePromptInput.value?.focus()
    return
  }

  const noteId = titlePromptNoteId
  if (!noteId || nodes.current?.noteId !== noteId) {
    finishTitlePrompt(false)
    return
  }

  titlePromptBusy.value = true
  try {
    const updated = await saveTitle(title)
    if (!updated) return
    page.noteTitle = updated.title
    titleDraft.value = updated.title
    titleDraftNoteId = updated.noteId
    finishTitlePrompt(true)
  } finally {
    titlePromptBusy.value = false
  }
}

async function deleteUntitledNoteAndLeave() {
  if (titlePromptBusy.value) return
  const noteId = titlePromptNoteId
  const note = nodes.current
  if (!noteId || note?.noteId !== noteId || note.title.trim()) {
    finishTitlePrompt(false)
    return
  }

  titlePromptBusy.value = true
  try {
    await nodes.deleteForever(noteId)
    finishTitlePrompt(true)
  } catch (e) {
    toast(`删除无标题笔记失败：${(e as Error).message}`, 'error')
  } finally {
    titlePromptBusy.value = false
  }
}

async function ensureTitleBeforeLeaving(): Promise<boolean> {
  await pageRef.value?.finishEditing()
  if (pendingTitleCommit) await pendingTitleCommit
  if (!allowNoteManagement.value) return true
  const note = nodes.current
  if (!note || note.title.trim()) return true

  let title = titleDraftNoteId === note.noteId ? titleDraft.value.trim() : ''
  if (!title) return requestTitlePrompt(note)

  const updated = await saveTitle(title)
  if (!updated) return false
  page.noteTitle = updated.title
  titleDraft.value = updated.title
  titleDraftNoteId = updated.noteId
  return true
}

onBeforeRouteLeave(() => ensureTitleBeforeLeaving())
onBeforeRouteUpdate((to, from) =>
  to.params.id === from.params.id ? true : ensureTitleBeforeLeaving(),
)

async function applyRoute() {
  const id = route.params.id as string | undefined
  if (!id) {
    nodes.current = null
    page.noteId = null
    titleDraft.value = ''
    titleDraftNoteId = null
    return
  }
  if (nodes.current?.noteId === id && page.noteId === id) return
  await nodes.select(id)
  if (nodes.current) {
    // 卡片 ID 清单来自笔记详情，几何与正文由 page.load 按视口分批取。
    await page.load(id, nodes.current.cardIds ?? [], nodes.current.title)
    titleDraft.value = nodes.current.title
    titleDraftNoteId = nodes.current.noteId
  }
}

watch(() => route.params.id, applyRoute)
watch(
  () => route.name,
  (name) => {
    if (name === 'notes') void applyRoute()
  },
)
watch(() => page.noteTitle, (title) => {
  const currentNote = nodes.current
  if (!title || !currentNote || currentNote.noteId !== page.noteId) return
  currentNote.title = title
  const row = nodes.nodes.find((note) => note.noteId === currentNote.noteId)
  if (row) row.title = title
})
// ---------------------------------------------------------------- title

/** Open the rename field, seeded with the current title and ready to type in. */
async function startRename() {
  if (!allowNoteManagement.value) return
  titleDraft.value = nodes.current?.title ?? ''
  titleDraftNoteId = nodes.current?.noteId ?? null
  renameCancelled = false
  renaming.value = true
  await nextTick()
  titleInput.value?.focus()
  // A second tick so the v-model has patched `value` before selecting it, otherwise a fresh
  // rename starts with the old text and the caret at the end.
  await nextTick()
  titleInput.value?.select()
}

/**
 * Escape abandons the draft.
 *
 * The field is closed first; `renameCancelled` then tells the resulting blur to discard rather
 * than save. `preventDefault`/`stopPropagation` keep the window-level Escape handler from also
 * treating this as "close the context menu / cancel the page edit".
 */
function cancelRename() {
  renameCancelled = true
  renaming.value = false
}

async function commitTitle() {
  // Enter 提交会先关闭编辑态并卸载输入框，卸载引发的 blur 会带着同一份草稿再进本函数；
  // 此时首次保存尚未返回、current.title 仍是旧值，放行会发出第二个相同的更新请求。
  if (!renaming.value) return
  renaming.value = false
  if (!allowNoteManagement.value) {
    titleDraft.value = nodes.current?.title ?? ''
    titleDraftNoteId = nodes.current?.noteId ?? null
    return
  }
  if (renameCancelled) {
    renameCancelled = false
    titleDraft.value = nodes.current?.title ?? ''
    titleDraftNoteId = nodes.current?.noteId ?? null
    return
  }
  if (!nodes.current) return
  const next = titleDraft.value.trim()
  if (!next || next === nodes.current.title) return
  const detail = await saveTitle(next)
  if (detail) {
    page.noteTitle = detail.title
    titleDraft.value = detail.title
    titleDraftNoteId = detail.noteId
  }
}

// ---------------------------------------------------------------- page actions

async function addCard() {
  if (!pageRef.value) return
  const created = await page.addBlock(
    { x: 40 + (page.cards.length + page.pendingCount) * 24, y: 40 + (page.cards.length + page.pendingCount) * 24 },
    { contentMd: '' },
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

function onNodeContext(payload: { node: NoteMeta; x: number; y: number }) {
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

function onKeydown(e: KeyboardEvent) {
  const primary = navigator.platform.toLowerCase().includes('mac') ? e.metaKey : e.ctrlKey
  const target = e.target as HTMLElement
  const typing = ['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable
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

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeunload', onBeforeUnload)
  const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined
  if (route.params.id && navigation?.type === 'reload') {
    void router.replace('/notes')
  } else {
    void applyRoute()
  }
  // 面板已在启动时加载列表；这里只在列表意外为空时兜底重拉一次。
  if (nodes.notesView.length === 0 && !nodes.loadingList) void nodes.refreshAll()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <div class="notes-view">
    <div class="view-body">
      <!-- The list stays put next to the page: opening a note must not take the list away,
           or getting back to a neighbour means going through the sidebar again. -->
      <div class="list-dock" :class="{ collapsed: listCollapsed }">
        <NoteList
          v-show="!listCollapsed"
          ref="noteListRef"
          :nodes="nodes.notesView"
          :loading="nodes.loadingList"
          :current-id="nodes.currentId"
          :allow-note-management="allowNoteManagement"
          @open="openNode"
          @context="onNodeContext"
          @remove="requestDeleteNote"
        />
        <button
          class="list-rail-toggle"
          :title="listCollapsed ? '展开笔记列表' : '收起笔记列表'"
          @click="listCollapsed = !listCollapsed"
        >{{ listCollapsed ? '»' : '«' }}</button>
      </div>

      <template v-if="nodes.current">
        <div class="page-column">
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
                  v-for="tool in noteToolList"
                  :key="tool.pluginId"
                  :note-id="nodes.current.noteId"
                />
            </div>

          </header>

          <NotePage ref="pageRef" @context="onPageContext" />
          <div class="page-toolbar">
            <span class="small muted">双击空白处新建卡片 · Ctrl+V 粘贴 · 拖动卡片摆放 · 双击卡片输入</span>
          </div>
        </div>
      </template>

      <div v-else class="empty">
        <h3>选择一篇笔记开始</h3>
        <p v-if="allowNoteManagement" class="small">
          或按 <span class="kbd">{{ primaryLabel() }}</span> <span class="kbd">N</span> 新建一篇。
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
    <StatusBar />
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
/* 列表面板泊位：默认全宽展示，收起后只剩贴左缘的窄轨 */
.list-dock {
  position: relative;
  flex: 0 0 auto;
  display: flex;
  min-height: 0;
}
.list-dock.collapsed {
  width: 22px;
  border-right: 1px solid var(--c-border);
}
.list-rail-toggle {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 6;
  width: 18px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--c-border);
  border-radius: 5px;
  background: var(--c-surface);
  color: var(--c-text-3);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
}
.list-dock.collapsed .list-rail-toggle {
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}
.list-rail-toggle:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.page-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.page-toolbar {
  flex: 0 0 auto;
  padding: 5px var(--space-4);
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
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
