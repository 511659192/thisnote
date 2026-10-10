<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import '@/plugins'
import { activeNoteTools } from '@/plugins'
import { usePageStore } from '@/stores/notePage'
import { useUiStore } from '@/stores/ui'
import NoteList from '@/components/NoteList.vue'
import NotePage from '@/components/page/NotePage.vue'
import StatusBar from '@/components/StatusBar.vue'
import NoteInfoPanel from '@/components/NoteInfoPanel.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import GroupPicker from '@/components/GroupPicker.vue'
import { primaryLabel } from '@/core/utils'
import type { NoteMeta, CardView, Notebook } from '@/api/types'

const route = useRoute()
const router = useRouter()
const nodes = useNoteStore()
const page = usePageStore()
const ui = useUiStore()

const pageRef = ref<InstanceType<typeof NotePage> | null>(null)
/** 插件贡献的笔记工具（按插件启用状态过滤）。 */
const noteToolList = computed(() => activeNoteTools())
const menu = ref<{ open: boolean; x: number; y: number; entries: MenuEntry[] }>({
  open: false,
  x: 0,
  y: 0,
  entries: [],
})
const renaming = ref(false)
const titleDraft = ref('')
const titleInput = ref<HTMLInputElement | null>(null)
/**
 * Set by Escape so the blur that follows cannot persist the abandoned draft.
 *
 * Unmounting the input (which is how the rename ends) fires `blur`, and `commitTitle` used to
 * run from that blur regardless of *why* the field closed — so Escape saved the edit instead of
 * discarding it. The flag makes "cancel" reach the blur handler.
 */
let renameCancelled = false
const tagDraft = ref('')
const addingTag = ref(false)

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6', '#64748b']

const modKey = computed(() => primaryLabel())

/** `nodes.current` is already the full detail payload, so the panel can take it as-is. */
const docForPanel = computed(() => nodes.current)

function openNode(id: string) {
  void router.push(`/notes/${id}`)
}

async function applyRoute() {
  const id = route.params.id as string | undefined
  if (!id) {
    nodes.current = null
    page.noteId = null
    return
  }
  await nodes.select(id)
  if (nodes.current) {
    // 卡片清单来自笔记详情（只有身份），几何与正文由 page.load 按视口分批取。
    await page.load(id, (nodes.current.cards ?? []).map((c) => c.id))
    titleDraft.value = nodes.current.title
  }
}

watch(() => route.params.id, applyRoute)
watch(
  () => route.name,
  (name) => {
    if (name === 'notes') void applyRoute()
  },
)

// ---------------------------------------------------------------- title & tags

/** Open the rename field, seeded with the current title and ready to type in. */
async function startRename() {
  titleDraft.value = nodes.current?.title ?? ''
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
  renaming.value = false
  if (renameCancelled) {
    renameCancelled = false
    titleDraft.value = nodes.current?.title ?? ''
    return
  }
  if (!nodes.current) return
  const next = titleDraft.value.trim()
  if (!next || next === nodes.current.title) return
  const detail = await nodes.updateCurrent({ title: next })
  if (detail) {
    page.noteTitle = detail.title
    await page.reload()
  }
}

async function submitTag() {
  const name = tagDraft.value.trim()
  tagDraft.value = ''
  addingTag.value = false
  if (!name) return
  await nodes.addTag(name)
  await page.reload()
}

async function removeTag(name: string) {
  await nodes.removeTag(name)
  await page.reload()
}

// ---------------------------------------------------------------- page actions

async function addCard() {
  if (!pageRef.value) return
  const created = await page.addBlock(
    { x: 40 + page.count * 24, y: 40 + page.count * 24 },
    { contentMd: '' },
  )
  if (created) await pageRef.value.beginEdit(created)
}

function onPageContext(p: { x: number; y: number; target: 'page' | 'card'; view?: CardView }) {
  const entries: MenuEntry[] = []
  if (p.target === 'card') {
    entries.push(
      { label: '编辑卡片内容', action: () => p.view && void pageRef.value?.beginEdit(p.view) },
      { label: '复制卡片', shortcut: 'Ctrl D', action: () => void page.duplicateSelected() },
      { separator: true },
      { label: '置于顶层', action: () => void page.zOrder('top') },
      { label: '置于底层', action: () => void page.zOrder('bottom') },
      { label: '上移一层', action: () => void page.zOrder('up') },
      { label: '下移一层', action: () => void page.zOrder('down') },
      { separator: true },
      {
        label: '用该卡片新建一篇笔记',
        action: async () => {
          const view = p.view
          if (!view) return
          const detail = await nodes.createNode({
            title: view.contentMd.split('\n')[0]?.replace(/^#+\s*/, '').slice(0, 40) || '新笔记',
            contentMd: view.contentMd,
          })
          if (detail) {
            ui.toast('已用卡片内容新建笔记', 'success')
            openNode(detail.id)
          }
        },
      },
      { separator: true },
      {
        label: '删除卡片（保留笔记内容）',
        danger: true,
        action: () => p.view && void page.removeBlock(p.view.card.id, false),
      },
      {
        label: '删除卡片并删除整篇笔记',
        danger: true,
        action: () => p.view && void page.removeBlock(p.view.card.id, true),
      },
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

/** 「移到分组…」的选择器：归属是唯一的，选中即替换原来的分组。 */
const groupPicker = ref<{ open: boolean; noteId: string | null }>({ open: false, noteId: null })

function openGroupPicker(noteId: string) {
  groupPicker.value = { open: true, noteId }
}

async function pickGroup(notebookId: string | null) {
  const noteId = groupPicker.value.noteId
  groupPicker.value = { open: false, noteId: null }
  if (!noteId || !notebookId) return
  try {
    await nodes.moveNoteToGroup(noteId, notebookId)
    ui.toast('已移到该分组', 'success')
  } catch (e) {
    ui.toast(`加入失败：${(e as Error).message}`, 'error')
  }
}

async function createGroupFromPicker() {
  const title = window.prompt('新建分组名称')
  if (!title || !title.trim()) return
  const created = await nodes.createGroup(title.trim(), null)
  await pickGroup(created.id)
}

/** 笔记本下拉的选项：整棵分组树按深度平铺（不受侧栏折叠影响）。 */
const notebookOptions = computed(() => {
  const out: { group: Notebook; depth: number }[] = []
  const walk = (parent: string | null, depth: number) => {
    for (const g of nodes.notebooks.filter((n) => (n.parentId ?? null) === parent)) {
      out.push({ group: g, depth })
      walk(g.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
})

async function changeNotebook(notebookId: string) {
  const current = nodes.current
  if (!current || current.notebooks?.[0]?.id === notebookId) return
  await nodes.moveNoteToGroup(current.id, notebookId)
  const picked = nodes.notebooks.find((n) => n.id === notebookId)
  if (picked) nodes.current = { ...current, notebooks: [picked] }
}

function onNodeContext(payload: { node: NoteMeta; x: number; y: number }) {
  const node = payload.node
  const entries: MenuEntry[] = [
    { label: '打开', action: () => openNode(node.id) },
    { label: '移到分组…', action: () => openGroupPicker(node.id) },
  ]
  menu.value = { open: true, x: payload.x, y: payload.y, entries }
}

// ---------------------------------------------------------------- shortcuts

function onKeydown(e: KeyboardEvent) {
  const primary = navigator.platform.toLowerCase().includes('mac') ? e.metaKey : e.ctrlKey
  const target = e.target as HTMLElement
  const typing = ['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable
  if (primary && e.key.toLowerCase() === 's' && !typing) {
    e.preventDefault()
    void nodes.flushAll()
  }
  if (e.key === 'Escape') menu.value.open = false
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  void applyRoute()
  // 面板已在启动时加载列表；这里只在列表意外为空时兜底重拉一次。
  if (nodes.notesView.length === 0 && !nodes.loadingList) void nodes.refreshAll()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="notes-view">
    <div class="view-body">
      <!-- The list stays put next to the page: opening a note must not take the list away,
           or getting back to a neighbour means going through the sidebar again. -->
      <NoteList
        :nodes="nodes.notesView"
        :loading="nodes.loadingList"
        :current-id="nodes.currentId"
        @open="openNode"
        @context="onNodeContext"
        @remove="(id: string) => nodes.deleteForever(id)"
      />

      <template v-if="nodes.current">
        <div class="page-column">
          <!-- The note's own header belongs to the page column, not to the whole view: it must
               sit beside the list rather than across the top of it. -->
          <header class="note-head">
            <div class="head-main">
              <input
                v-if="renaming"
                ref="titleInput"
                v-model="titleDraft"
                class="title-input"
                @keydown.enter.prevent="commitTitle"
                @keydown.esc.prevent.stop="cancelRename"
                @blur="commitTitle"
              />
              <h2
                v-else
                class="title"
                title="双击重命名"
                @dblclick="startRename"
              >
                {{ nodes.current.title }}
              </h2>
                              <!-- 实体插件贡献的笔记工具（收藏就是这一类）——核心不认识任何具体插件 -->
                <component
                  :is="tool.component"
                  v-for="tool in noteToolList"
                  :key="tool.pluginId"
                  :note-id="nodes.current.id"
                />
            </div>

            <div class="head-meta">
              <select
                class="notebook-select"
                title="所属分组"
                :value="nodes.current.notebooks?.[0]?.id ?? ''"
                @change="changeNotebook(($event.target as HTMLSelectElement).value)"
              >
                <option v-for="row in notebookOptions" :key="row.group.id" :value="row.group.id">
                  {{ '　'.repeat(row.depth) }}{{ row.group.title }}
                </option>
              </select>
              <span v-for="t in page.tags" :key="t.id" class="chip tag-chip">
                #{{ t.name }}
                <button class="x" title="移除标签" @click="removeTag(t.name)">✕</button>
              </span>
              <input
                v-if="addingTag"
                v-model="tagDraft"
                class="tag-input"
                placeholder="标签名，回车确认"
                @keydown.enter="submitTag"
                @keydown.esc="addingTag = false"
                @blur="submitTag"
              />
              <button v-else class="chip add-tag" @click="addingTag = true">＋ 标签</button>
              <div class="spacer"></div>
              <!-- Quiet until it matters: the running card count is only worth showing once the
                   note has actually hit its cap. -->
              <span v-if="page.full" class="small limit-full">
                已达上限 {{ page.maxBlocks }} 张卡片
              </span>
            </div>
          </header>

          <NotePage
            ref="pageRef"
            @context="onPageContext"
            @open-note="openNode"
            @create-note="
              async (title: string) => {
                // 标题已被占用：直接打开那一篇；还没人用过这个名字：当场新建一篇。
                // resolveStub 在后端就是「有就返回、没有就建」，给不出结果时才需要自己兜底。
                try {
                  const d = await nodes.resolveStub(title)
                  if (d) {
                    openNode(d.id)
                    return
                  }
                } catch {
                  // 没有占位就是这条路径，继续往下新建
                }
                const fresh = await nodes.createAndSelect({ title })
                if (fresh) openNode(fresh.id)
              }
            "
            @open-tag="
              (tag: string) => {
                nodes.setTag(tag)
                void router.push('/')
              }
            "
          />
          <div class="page-toolbar">
            <span class="small muted">双击空白处新建卡片 · Ctrl+V 粘贴 · 拖动卡片摆放 · 双击卡片输入</span>
          </div>
        </div>
        <NoteInfoPanel
          v-if="ui.rightPanelOpen && docForPanel"
          :doc="docForPanel"
          @create="
            async (title: string) => {
              const d = await nodes.resolveStub(title)
              if (d) openNode(d.id)
            }
          "
        />
      </template>

      <div v-else class="empty">
        <h3>选择一篇笔记开始</h3>
        <p class="small">
          或按 <span class="kbd">{{ primaryLabel() }}</span> <span class="kbd">N</span> 新建一篇。
        </p>
      </div>
    </div>

    <ContextMenu :open="menu.open" :x="menu.x" :y="menu.y" :entries="menu.entries" @close="menu.open = false" />
    <GroupPicker
      :open="groupPicker.open"
      :rows="nodes.visibleGroups"
      title="移到分组"
      @pick="pickGroup"
      @create="createGroupFromPicker"
      @close="groupPicker = { open: false, noteId: null }"
    />
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
.head-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}
.notebook-select {
  padding: 2px 6px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface-2);
  color: var(--c-text-2);
  font-size: 12px;
}
.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: var(--accent-soft);
  color: var(--accent);
}
.tag-chip .x {
  font-size: 10px;
  opacity: 0.6;
}
.tag-chip .x:hover {
  opacity: 1;
}
.add-tag {
  border: 1px dashed var(--c-border-strong);
  background: transparent;
  cursor: pointer;
}
.add-tag:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.tag-input {
  width: 140px;
  padding: 1px 8px;
  font-size: 11.5px;
  border-radius: 999px;
  border: 1px solid var(--accent);
  outline: none;
  background: var(--c-surface);
}
.limit-full {
  color: var(--danger);
  font-weight: 600;
}

.view-body {
  flex: 1;
  display: flex;
  min-height: 0;
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
</style>
