<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { useUiStore } from '@/stores/ui'
import { notebookApi } from '@/api'
import { activeNoteListFilters, type NoteListFilter } from '@thisnode/plugin-api'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { ListFilter, Notebook } from '@/api/types'

/**
 * 笔记域的侧栏主面板：新建、视图筛选、分组树、插件筛选与标签。
 *
 * <p>前后端分离后整个侧栏容器归宿主，业务面板由各域经
 * {@code registerSidebarPanel} 登记（本组件即 notes 的登记物）；
 * 插件 nav 入口（复习/收藏徽标轨）归宿主的 PluginNavRail，不在这里。
 */
const nodes = useNoteStore()
const router = useRouter()
const route = useRoute()

/** 本面板的选中态只在笔记路由上点亮；插件页面打开时让位。 */
const onNotes = computed(() => route.path === '/notes' || route.path.startsWith('/notes/'))

const filters = computed<{ key: ListFilter; label: string; icon: string }[]>(() => [
  { key: 'all', label: '全部笔记', icon: '▤' },
  { key: 'recent', label: '最近编辑', icon: '⏱' },
])

/** 插件提供的列表筛选（例如「收藏」）：和核心筛选同级，只是给同一份列表加一个条件。 */
const pluginFilters = computed<NoteListFilter[]>(() => activeNoteListFilters())

function openPluginFilter(filter: NoteListFilter) {
  void nodes.setPluginFilter(filter.pluginId, filter.id)
  void router.push('/notes')
}

function openFilter(key: ListFilter) {
  nodes.clearPluginFilter()
  nodes.setFilter(key)
  void router.push('/notes')
}

function openTag(name: string) {
  nodes.clearPluginFilter()
  nodes.setTag(name)
  void router.push('/notes')
}

function openNotebook(id: string) {
  nodes.clearPluginFilter()
  nodes.openGroup(id)
  void router.push('/notes')
}

async function createNote() {
  const detail = await nodes.createAndSelect({ title: '无标题笔记', contentMd: '' })
  if (detail) void router.push(`/notes/${detail.id}`)
}

// ---------------------------------------------------------------- 分组树

/** 默认分组的固定 id（与后端 NotebookRepository.DEFAULT_GROUP_ID 一致）。 */
const DEFAULT_GROUP_ID = 'grp-default'

/** 拖拽悬停中的分组（落点高亮）。 */
const dragOverGroupId = ref<string | null>(null)
const groupMenu = ref<{ open: boolean; x: number; y: number; entries: MenuEntry[] }>({
  open: false,
  x: 0,
  y: 0,
  entries: [],
})

async function promptNewGroup(parentId: string | null) {
  const label = parentId ? '新建子分组名称' : '新建分组名称'
  const title = window.prompt(label)
  if (!title || !title.trim()) return
  try {
    await nodes.createGroup(title.trim(), parentId)
    ui.toast('分组已创建', 'success')
  } catch (e) {
    ui.toast(`创建失败：${(e as Error).message}`, 'error')
  }
}

async function renameGroup(id: string, current: string) {
  const title = window.prompt('重命名分组', current)
  if (!title || !title.trim() || title === current) return
  await nodes.renameGroup(id, title.trim())
}

async function removeGroup(id: string, title: string) {
  if (!window.confirm(`删除分组「${title}」？\n\n分组里的笔记不会被删除，只是不再属于这个分组。`)) return
  try {
    await nodes.deleteGroup(id)
    ui.toast('分组已删除', 'success')
  } catch (e) {
    ui.toast(`删除失败：${(e as Error).message}`, 'error')
  }
}

/** 把分组移到另一个分组下（或移到顶层）。 */
async function moveGroupTo(id: string, parentId: string | null) {
  try {
    await nodes.moveGroup(id, parentId)
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  }
}

function onGroupContext(e: MouseEvent, group: Notebook) {
  const entries: MenuEntry[] = [
    { label: '打开', action: () => openNotebook(group.id) },
    { label: '新建子分组', action: () => void promptNewGroup(group.id) },
    { label: '重命名', action: () => void renameGroup(group.id, group.title) },
    { separator: true },
    { label: '移到顶层', disabled: !group.parentId, action: () => void moveGroupTo(group.id, null) },
    {
      label: '移入其他分组…',
      disabled: nodes.rootGroups.filter((g) => g.id !== group.id).length === 0,
      action: () => {
        const candidates = nodes.rootGroups.filter((g) => g.id !== group.id)
        const list = candidates.map((g, i) => `${i + 1}. ${g.title}`).join('\n')
        const answer = window.prompt(`把「${group.title}」移到哪个分组下？输入序号：\n\n${list}`)
        const index = Number(answer) - 1
        if (candidates[index]) void moveGroupTo(group.id, candidates[index].id)
      },
    },
    { separator: true },
    {
      label: '删除分组',
      danger: true,
      // 默认分组是「无归属」笔记的落脚点，删了就不变量就破了 —— 界面先挡一道（后端也会拒）
      disabled: group.id === DEFAULT_GROUP_ID,
      action: () => void removeGroup(group.id, group.title),
    },
  ]
  groupMenu.value = { open: true, x: e.clientX, y: e.clientY, entries }
}

function onGroupDragStart(e: DragEvent, id: string) {
  e.dataTransfer?.setData('application/x-thisnode-group', id)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onGroupDragLeave(id: string) {
  if (dragOverGroupId.value === id) dragOverGroupId.value = null
}

/**
 * 树节点的落点：拖来的是笔记 → 加进这个分组；拖来的是分组 → 把自己变成它的子分组。
 *
 * <p>笔记的拖拽源是笔记列表行（本来就带 {@code application/x-thisnode-node}，画布拖拽也在用），
 * 这里只是复用它；分组用自己的 {@code application/x-thisnode-group} 与笔记区分开，
 * 于是同一个落点能各管各的（笔记是**改归属**，分组是改父级）。
 */
async function onGroupDrop(e: DragEvent, groupId: string) {
  dragOverGroupId.value = null
  const dt = e.dataTransfer
  if (!dt) return
  const noteId = dt.getData('application/x-thisnode-node') || dt.getData('application/x-thisnode-note')
  if (noteId) {
    try {
      await nodes.moveNoteToGroup(noteId, groupId)
      ui.toast('已移到该分组', 'success')
    } catch (err) {
      ui.toast(`加入失败：${(err as Error).message}`, 'error')
    }
    return
  }
  const draggedGroupId = dt.getData('application/x-thisnode-group')
  if (draggedGroupId) {
    await moveGroupTo(draggedGroupId, groupId)
  }
}

watch(
  () => nodes.notebooks.length,
  (len) => {
    if (len === 0) void nodes.refreshMeta()
  },
  { immediate: true },
)
</script>

<template>
  <aside class="sidebar-panel">
    <div class="section">
      <button class="new-note" @click="createNote">
        <span class="plus">＋</span>
        <span>新建笔记</span>
      </button>
    </div>
    <nav class="section scroll">
      <div class="group-title">笔记</div>
      <template v-for="f in filters" :key="f.key">
        <button
          class="nav-item"
          :class="{ active: onNotes && nodes.filter === f.key && !nodes.activeTag && !nodes.activeNotebookId }"
          @click="openFilter(f.key)"
        >
          <!-- 「全部笔记」的图标就是「新建分组」按钮：悬停时 ▤ 变 ＋（不额外占一行） -->
          <span
            v-if="f.key === 'all'"
            class="ico-swap"
            title="新建分组"
            @click.stop="promptNewGroup(null)"
          >
            <span class="ico base">{{ f.icon }}</span>
            <span class="ico add">＋</span>
          </span>
          <span v-else class="ico">{{ f.icon }}</span>
          <span class="label">{{ f.label }}</span>
          <!-- 「全部笔记」是分组树的根：分组就是它的下一层；鼠标移上去图标变＋＝新建分组 -->
          <span
            v-if="f.key === 'all'"
            class="caret root-caret"
            :title="nodes.groupsShown ? '收起分组' : '展开分组'"
            @click.stop="nodes.groupsShown = !nodes.groupsShown"
          >{{ nodes.groupsShown ? '▾' : '▸' }}</span>
        </button>

        <!-- 分组树：挂在「全部笔记」下面，可无限嵌套（后端 notebooks.parent_id 本来就是这个意思） -->
        <template v-if="f.key === 'all' && nodes.groupsShown">
          <button
            v-for="row in nodes.visibleGroups"
            :key="row.group.id"
            class="nav-item group-item"
            :class="{
              active: nodes.activeNotebookId === row.group.id,
              'drop-target': dragOverGroupId === row.group.id,
            }"
            :style="{ paddingLeft: 8 + row.depth * 14 + 'px' }"
            :title="row.group.title"
            draggable="true"
            @click="openNotebook(row.group.id)"
            @dragstart="onGroupDragStart($event, row.group.id)"
            @contextmenu.prevent="onGroupContext($event, row.group)"
            @dragover.prevent="dragOverGroupId = row.group.id"
            @dragleave="onGroupDragLeave(row.group.id)"
            @drop.prevent="onGroupDrop($event, row.group.id)"
          >
            <span
              class="caret"
              :class="{ invisible: !nodes.hasChildren(row.group.id) }"
              @click.stop="nodes.toggleGroup(row.group.id)"
            >{{ nodes.isGroupExpanded(row.group.id) ? '▾' : '▸' }}</span>
            <!-- 悬停时 ▣ 变 ＋：在这个分组下新建子分组 -->
            <span class="ico-swap" title="新建子分组" @click.stop="promptNewGroup(row.group.id)">
              <span class="ico base">▣</span>
              <span class="ico add">＋</span>
            </span>
            <span class="label">{{ row.group.title }}</span>
            <span v-if="row.group.count" class="count">{{ row.group.count }}</span>
          </button>
        </template>

      </template>
      <!-- 插件提供的筛选（例如「收藏」）：与核心筛选同组同级，因为它就是同一份列表的一个条件 -->
      <button
        v-for="pf in pluginFilters"
        :key="pf.id"
        class="nav-item"
        :class="{ active: onNotes && nodes.pluginFilter?.filterId === pf.id }"
        @click="openPluginFilter(pf)"
      >
        <span class="ico">{{ pf.icon || '★' }}</span>
        <span class="label">{{ pf.label }}</span>
      </button>

      <template v-if="nodes.tags.length">
        <div class="group-title">标签</div>
        <div class="tags">
          <button
            v-for="t in nodes.tags.slice(0, 40)"
            :key="t.id"
            class="tag"
            :class="{ active: nodes.activeTag === t.name }"
            :title="`${t.count} 篇`"
            @click="openTag(t.name)"
          >
            #{{ t.name }}<span class="tag-count">{{ t.count }}</span>
          </button>
        </div>
      </template>
    </nav>
  </aside>

  <ContextMenu
    :open="groupMenu.open"
    :x="groupMenu.x"
    :y="groupMenu.y"
    :entries="groupMenu.entries"
    @close="groupMenu.open = false"
  />
</template>

<style scoped>
.sidebar-panel {
  width: var(--sidebar-w);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.section {
  padding: var(--space-2) var(--space-2) 0;
}
.section.scroll {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding-bottom: var(--space-3);
}

.new-note {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 7px 10px;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}
.new-note:hover {
  filter: brightness(1.07);
}
.new-note .plus {
  font-size: 15px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: var(--space-3) var(--space-2) 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--c-text-3);
}

/* ---- 分组树 ---- */
.caret {
  flex: 0 0 auto;
  width: 12px;
  text-align: center;
  font-size: 9px;
  opacity: 0.65;
  cursor: pointer;
}
.caret.invisible {
  visibility: hidden;
  cursor: default;
}
.root-caret {
  margin-left: auto;
  padding: 0 2px;
}
.group-item {
  font-size: 12.5px;
}
.group-item .ico {
  opacity: 0.5;
  font-size: 10px;
}
.group-item.drop-target {
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  outline: 1px dashed color-mix(in srgb, var(--accent) 60%, transparent);
  outline-offset: -2px;
}
.ico-swap {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  cursor: pointer;
}
.ico-swap .add {
  display: none;
  color: var(--accent);
  font-weight: 700;
}
.nav-item:hover .ico-swap .base {
  display: none;
}
.nav-item:hover .ico-swap .add {
  display: inline;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 5px var(--space-2);
  border-radius: var(--radius-sm);
  color: var(--c-text-2);
  font-size: 13px;
  text-align: left;
}
.nav-item:hover {
  background: var(--c-surface-3);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 500;
}
.nav-item .ico {
  width: 16px;
  text-align: center;
  opacity: 0.8;
}
.nav-item .label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav-item .count {
  font-size: 11px;
  color: var(--c-text-3);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 2px var(--space-2);
}
.tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--c-surface-3);
  color: var(--c-text-2);
  font-size: 11.5px;
}
.tag:hover {
  background: var(--accent-soft);
  color: var(--accent);
}
.tag.active {
  background: var(--accent);
  color: #fff;
}
.tag-count {
  opacity: 0.6;
  font-size: 10px;
}
</style>
