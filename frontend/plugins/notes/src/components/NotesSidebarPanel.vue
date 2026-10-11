<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { useUiStore } from '@/stores/ui'
import { notebookApi } from '@/api'
import {
  activeListFilter_,
  activeNavEntries,
  activeNoteListFilters,
  clearActiveListFilter,
  navBadges,
  refreshPluginAvailability,
  setActiveListFilter,
  type NoteListFilter,
} from '@thisnode/plugin-api'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { Notebook } from '@/api/types'

/**
 * 笔记域的侧栏主面板：新建、视图筛选、分组树、插件筛选、插件入口与标签。
 *
 * <p>产物分离后侧栏容器归宿主，本面板由 notes 经 registerSidebarPanel 登记。
 * 插件入口（复习/收藏徽标轨）与插件筛选同样只面向注册器渲染 ——
 * 宿主依旧不认识任何具体插件，展示形态与拆分前完全一致。
 */
const nodes = useNoteStore()
const router = useRouter()
const route = useRoute()

/**
 * 侧栏的插件入口（原来是硬编码的「复习」按钮 —— 那等于让核心认识记忆曲线插件）。
 * 现在只面向注册器：入口由插件登记，徽标数量由插件自己算，这里一个具体插件都不认识。
 */
const pluginNav = computed(() => activeNavEntries())

/** 当前是否停在某个插件页面上：入口与核心视图同级，同一时刻只该有一行是亮的。 */
const isPluginPage = computed(() => pluginNav.value.some((entry) => route.path === entry.to))
const badges = ref<Record<string, number>>({})

async function refreshPluginBadges() {
  /**
   * 不给「你正在看的那个插件页」取徽标：那份数据页面自己刚刚取过一遍，
   * 再取一次就是同一份列表请求打两遍（从复习切到收藏时实测到过）。
   * 等你离开这个页面时，它会被正常刷新。
   */
  const elsewhere = pluginNav.value.filter((entry) => route.path !== entry.to)
  badges.value = await navBadges(elsewhere)
}
async function refreshPlugins() {
  await refreshPluginAvailability()
  await refreshPluginBadges()
}
/**
 * 两个触发器各管各的，**切路由不再拉可用性快照**：
 *
 * - <b>可用性快照</b>（GET /api/plugins）：只在进应用、窗口获得焦点/变为可见、以及设置页
 *   启停/重载之后拉。qiankun 生命周期之后，本窗口内的启停会直接装卸微应用并注销插槽，
 *   注册表本身就是本窗口的权威；跨窗口/脚本/后端侧的外部变化，由「切回窗口」的焦点事件兜底。
 * - <b>徽标</b>：切路由时刷新（从复习页回来，due 数就该变），有 3 秒在飞合并兜底。
 */
watch(() => route.fullPath, () => void refreshPluginBadges())
onMounted(async () => {
  // 原 App.vue 的启动加载随域迁入：列表在应用启动时装好（面板跨路由常驻，
  // 设置页等非笔记路由的侧栏也得有行可点）。
  await nodes.refreshAll()
  void refreshPlugins()
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
})
function onFocus() {
  void refreshPlugins()
}
function onVisibility() {
  if (document.visibilityState === 'visible') void refreshPlugins()
}

/** 插件提供的列表筛选（例如「收藏」）：和核心的「全部笔记」同级，只是给同一份列表加一个条件。 */
/** 侧栏只渲染可见筛选：hidden 的筛选（如搜索框）由插件自己的 UI 激活，不占侧栏行。 */
const pluginFilters = computed<NoteListFilter[]>(() => activeNoteListFilters().filter((f) => !f.hidden))

function openPluginFilter(filter: NoteListFilter) {
  setActiveListFilter(filter.pluginId, filter.id)
  void router.push('/notes')
}

function openAll() {
  clearActiveListFilter()
  nodes.showAll()
  void router.push('/notes')
}

function openNotebook(id: string) {
  clearActiveListFilter()
  nodes.openGroup(id)
  void router.push('/notes')
}

async function createNote() {
  const detail = await nodes.createAndSelect({ contentMd: '' })
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

async function promptNewGroup() {
  const title = window.prompt('新建分组名称')
  if (!title || !title.trim()) return
  try {
    await nodes.createGroup(title.trim())
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

function onGroupDragOver(e: DragEvent, id: string) {
  if (e.dataTransfer?.types.includes('application/x-thisnode-group')) {
    dragOverGroupId.value = id
  }
}

function onGroupDragLeave(id: string) {
  if (dragOverGroupId.value === id) dragOverGroupId.value = null
}

/** 拖入分组时把它变成目标分组的子分组；笔记不再支持拖入分组树转移。 */
async function onGroupDrop(e: DragEvent, groupId: string) {
  dragOverGroupId.value = null
  const draggedGroupId = e.dataTransfer?.getData('application/x-thisnode-group')
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
      <button
        class="nav-item"
        :class="{ active: !isPluginPage && !nodes.activeNotebookId }"
        @click="openAll()"
      >
        <!-- 图标就是「新建分组」按钮：悬停时 ▤ 变 ＋（不额外占一行） -->
        <span class="ico-swap" title="新建分组" @click.stop="promptNewGroup()">
          <span class="ico base">▤</span>
          <span class="ico add">＋</span>
        </span>
        <span class="label">全部笔记</span>
        <!-- 「全部笔记」是分组树的根：分组就是它的下一层 -->
        <span
          class="caret root-caret"
          :title="nodes.groupsShown ? '收起分组' : '展开分组'"
          @click.stop="nodes.groupsShown = !nodes.groupsShown"
        >{{ nodes.groupsShown ? '▾' : '▸' }}</span>
      </button>

        <!-- 分组树：挂在「全部笔记」下面，可无限嵌套（后端 notebooks.parent_id 本来就是这个意思） -->
        <template v-if="nodes.groupsShown">
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
            @dragover.prevent="onGroupDragOver($event, row.group.id)"
            @dragleave="onGroupDragLeave(row.group.id)"
            @drop.prevent="onGroupDrop($event, row.group.id)"
          >
            <span
              class="caret"
              :class="{ invisible: !nodes.hasChildren(row.group.id) }"
              @click.stop="nodes.toggleGroup(row.group.id)"
            >{{ nodes.isGroupExpanded(row.group.id) ? '▾' : '▸' }}</span>
            <span class="label">{{ row.group.title }}</span>
            <span v-if="row.group.count" class="count">{{ row.group.count }}</span>
          </button>
        </template>

      <!-- 插件提供的筛选（例如「收藏」）：与核心筛选同组同级，因为它就是同一份列表的一个条件 -->
      <button
        v-for="pf in pluginFilters"
        :key="pf.id"
        class="nav-item"
        :class="{ active: !isPluginPage && activeListFilter_()?.filterId === pf.id }"
        @click="openPluginFilter(pf)"
      >
        <span class="ico">{{ pf.icon || '★' }}</span>
        <span class="label">{{ pf.label }}</span>
      </button>

      <!--
        插件贡献的入口（复习 / 收藏…）与上面的核心视图**同一层级**：同样的行、同样的选中态、
        同样的计数位 —— 还原拆分前的展示形式。核心依旧不认识任何一个具体插件。
      -->
      <button
        v-for="entry in pluginNav"
        :key="entry.pluginId"
        class="nav-item plugin-entry"
        :class="{ active: route.path === entry.to }"
        @click="$router.push(entry.to)"
      >
        <span class="ico">{{ entry.glyph }}</span>
        <span class="label">{{ entry.label }}</span>
        <span v-if="(badges[entry.pluginId] ?? 0) > 0" class="count">
          {{ (badges[entry.pluginId] ?? 0) > 50 ? '50+' : badges[entry.pluginId] }}
        </span>
      </button>

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

</style>
