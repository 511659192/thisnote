<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { bookApi } from '@/api'
import {
  activeListFilter_,
  activeNavEntries,
  activeNoteListFilters,
  clearActiveListFilter,
  ConfirmDialog,
  navBadges,
  onPluginStateChange,
  refreshPluginAvailability,
  setActiveListFilter,
  shellState,
  type NoteListFilter,
  toast,
} from '@thisnote/plugin-api'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import PromptDialog from '@/components/PromptDialog.vue'
import type { Book } from '@/api/types'

/**
 * 笔记域的侧栏主面板：新建、视图筛选、分组列表、插件筛选与插件入口。
 *
 * <p>产物分离后侧栏容器归宿主，本面板由 notes 经 registerSidebarPanel 登记。
 * 插件入口（复习/收藏徽标轨）与插件筛选同样只面向注册器渲染 ——
 * 宿主依旧不认识任何具体插件，展示形态与拆分前完全一致。
 */
const nodes = useNoteStore()
const router = useRouter()
const route = useRoute()

/**
 * 侧栏的插件入口（原来是硬编码的「复习」按钮 —— 那等于让核心认识记忆周期插件）。
 * 现在只面向注册器：入口由插件登记，徽标数量由插件自己算，这里一个具体插件都不认识。
 */
const pluginNav = computed(() => activeNavEntries())

const collapsed = computed(() => shellState.sidebarCollapsed)

/** 当前是否停在某个插件页面上：入口与核心视图同级，同一时刻只该有一行是亮的。 */
const isPluginPage = computed(() => pluginNav.value.some((entry) => route.path === entry.to))
const badges = ref<Record<string, number>>({})
let unsubscribePluginState: (() => void) | null = null

async function refreshPluginBadges(force = false) {
  /**
   * 不给「你正在看的那个插件页」取徽标：那份数据页面自己刚刚取过一遍，
   * 再取一次就是同一份列表请求打两遍（从复习切到收藏时实测到过）。
   * 等你离开这个页面时，它会被正常刷新。
   */
  const elsewhere = pluginNav.value.filter((entry) => route.path !== entry.to)
  badges.value = await navBadges(elsewhere, force)
}
async function refreshPlugins() {
  await refreshPluginAvailability()
  await refreshPluginBadges()
}
/**
 * 两个触发器各管各的，**切路由不再拉可用性快照**：
 *
 * - <b>可用性快照</b>（GET /api/plugins/get）：只在进应用、窗口获得焦点/变为可见、以及设置页
 *   启停/重载之后拉。qiankun 生命周期之后，本窗口内的启停会直接装卸微应用并注销插槽，
 *   注册表本身就是本窗口的权威；跨窗口/脚本/后端侧的外部变化，由「切回窗口」的焦点事件兜底。
 * - <b>徽标</b>：切路由时刷新（从复习页回来，due 数就该变），有 3 秒在飞合并兜底。
 */
watch(
  () => route.path,
  (path, previousPath) => {
    const leftPluginPage = pluginNav.value.some((entry) => entry.to === previousPath)
    const enteredPluginPage = pluginNav.value.some((entry) => entry.to === path)
    if (leftPluginPage && !enteredPluginPage) void refreshPluginBadges()
  },
)
onMounted(async () => {
  // 原 App.vue 的启动加载随域迁入：列表在应用启动时装好（面板跨路由常驻，
  // 设置页等非笔记路由的侧栏也得有行可点）。
  await nodes.refreshAll()
  void refreshPlugins()
  unsubscribePluginState = onPluginStateChange(() => {
    // 插件里改了数据（记忆曲线…）：侧栏的徽标立刻跟着刷新
    void refreshPluginBadges(true)
  })
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
  unsubscribePluginState?.()
  unsubscribePluginState = null
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

const activePluginFilter = computed<NoteListFilter | null>(() => {
  const active = activeListFilter_()
  if (!active) return null
  return activeNoteListFilters().find(
    (filter) => filter.pluginId === active.pluginId && filter.id === active.filterId,
  ) ?? null
})

function openPluginFilter(filter: NoteListFilter) {
  nodes.showAll()
  setActiveListFilter(filter.pluginId, filter.id)
  void router.push('/notes')
}

function openAll() {
  clearActiveListFilter()
  nodes.showAll()
  void router.push('/notes')
}

function openBook(id: string) {
  clearActiveListFilter()
  nodes.openGroup(id)
  void router.push('/notes')
}

function openPluginEntry(path: string) {
  void router.push(path)
}

/** 拖拽中的笔记 → 悬停的分组 id（用于高亮投放目标）。 */
const dragOverGroupId = ref<string | null>(null)

function onGroupDragOver(id: string, event: DragEvent) {
  if (!event.dataTransfer?.types.includes('application/x-thisnote-note')) return
  event.dataTransfer.dropEffect = 'move'
  dragOverGroupId.value = id
}

function onGroupDragLeave(id: string, event: DragEvent) {
  const target = event.relatedTarget as Node | null
  if (target && (event.currentTarget as HTMLElement).contains(target)) return
  if (dragOverGroupId.value === id) dragOverGroupId.value = null
}

async function onGroupDrop(group: Book, event: DragEvent) {
  dragOverGroupId.value = null
  const noteId = event.dataTransfer?.getData('application/x-thisnote-note')
  if (!noteId) return
  try {
    await nodes.moveMany([noteId], group.id)
    toast(`已转入「${group.title}」`, 'success')
  } catch (e) {
    toast(`转入失败：${(e as Error).message}`, 'error')
  }
}

// ---------------------------------------------------------------- 分组列表

/** 默认分组的固定 id（与后端 NotebookRepository.DEFAULT_GROUP_ID 一致）。 */
const DEFAULT_GROUP_ID = 'grp-default'

const groupMenu = ref<{ open: boolean; x: number; y: number; entries: MenuEntry[] }>({
  open: false,
  x: 0,
  y: 0,
  entries: [],
})
const confirmDialog = ref<{
  open: boolean
  title: string
  message: string
  action: (() => void | Promise<void>) | null
}>({ open: false, title: '', message: '', action: null })

const groupNameDialog = ref<{
  open: boolean
  title: string
  label: string
  value: string
  action: ((name: string) => Promise<void>) | null
}>({ open: false, title: '', label: '', value: '', action: null })

function promptNewGroup() {
  groupNameDialog.value = {
    open: true,
    title: '新建分组',
    label: '分组名称',
    value: '',
    action: async (name) => {
      try {
        await nodes.createGroup(name)
        toast('分组已创建', 'success')
      } catch (e) {
        toast(`创建失败：${(e as Error).message}`, 'error')
      }
    },
  }
}

function renameGroup(id: string, current: string) {
  groupNameDialog.value = {
    open: true,
    title: '重命名分组',
    label: '新的名称',
    value: current,
    action: async (name) => {
      if (name === current) return
      try {
        await nodes.renameGroup(id, name)
      } catch (e) {
        toast(`重命名失败：${(e as Error).message}`, 'error')
      }
    },
  }
}

async function confirmGroupName() {
  const { action, value } = groupNameDialog.value
  const name = value.trim()
  if (!name || !action) return
  groupNameDialog.value = { ...groupNameDialog.value, open: false, action: null }
  await action(name)
}

function cancelGroupName() {
  groupNameDialog.value = { ...groupNameDialog.value, open: false, action: null }
}

async function removeGroup(id: string) {
  try {
    await nodes.deleteGroup(id)
    toast('分组已删除', 'success')
  } catch (e) {
    toast(`删除失败：${(e as Error).message}`, 'error')
  }
}

function requestRemoveGroup(id: string, title: string) {
  confirmDialog.value = {
    open: true,
    title: `删除分组「${title}」？`,
    message: '分组里的笔记不会被删除，只是不再属于这个分组。',
    action: () => removeGroup(id),
  }
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

function onGroupContext(e: MouseEvent, group: Book) {
  const entries: MenuEntry[] = [
    { label: '打开', action: () => openBook(group.id) },
    { label: '重命名', action: () => void renameGroup(group.id, group.title) },
    { separator: true },
    {
      label: '删除分组',
      danger: true,
      disabled: group.id === DEFAULT_GROUP_ID,
      action: () => requestRemoveGroup(group.id, group.title),
    },
  ]
  groupMenu.value = { open: true, x: e.clientX, y: e.clientY, entries }
}

watch(
  () => nodes.books.length,
  (len) => {
    if (len === 0) void nodes.refreshMeta()
  },
  { immediate: true },
)
</script>

<template>
<aside class="sidebar-panel" :class="{ collapsed }">
    <nav class="section scroll">
      <div v-if="!collapsed" class="group-title">笔记</div>
      <button
        class="nav-item"
        :class="{ active: !isPluginPage && !nodes.activeBookId && !activePluginFilter }"
        @click="openAll()"
        :title="collapsed ? '全部笔记' : undefined"
      >
        <span class="ico base">▤</span>
        <span v-if="!collapsed" class="label">全部笔记</span>
        <span
          v-if="!collapsed"
          class="row-add"
          title="新建分组"
          @click.stop="promptNewGroup()"
        >＋</span>
        <!-- 「全部笔记」下方显示扁平分组列表。 -->
        <span
          v-if="!collapsed"
          class="caret root-caret"
          :title="nodes.groupsShown ? '收起分组' : '展开分组'"
          @click.stop="nodes.groupsShown = !nodes.groupsShown"
        >{{ nodes.groupsShown ? '▾' : '▸' }}</span>
      </button>

      <template v-if="!collapsed && nodes.groupsShown">
          <button
            v-for="group in nodes.books"
            :key="group.id"
            class="nav-item group-item"
            :class="{ active: nodes.activeBookId === group.id, 'drop-target': dragOverGroupId === group.id }"
            :title="group.title"
            @click="openBook(group.id)"
            @contextmenu.prevent="onGroupContext($event, group)"
            @dragover.prevent="onGroupDragOver(group.id, $event)"
            @dragleave="onGroupDragLeave(group.id, $event)"
            @drop.prevent="onGroupDrop(group, $event)"
          >
            <span class="label">{{ group.title }}</span>
          </button>
        </template>

      <!-- 插件提供的筛选（例如「收藏」）：与核心筛选同组同级，因为它就是同一份列表的一个条件 -->
      <button
        v-for="pf in pluginFilters"
        :key="pf.id"
        class="nav-item"
        :class="{ active: !isPluginPage && activePluginFilter?.pluginId === pf.pluginId && activePluginFilter?.id === pf.id }"
        @click="openPluginFilter(pf)"
      >
        <span class="ico" :title="collapsed ? pf.label : undefined">{{ pf.icon || '★' }}</span>
        <span v-if="!collapsed" class="label">{{ pf.label }}</span>
      </button>

      <!--
        插件贡献的入口（复习 / 收藏…）与上面的核心视图**同一层级**：同样的行、同样的选中态、
        同样的计数位 —— 还原拆分前的展示形式。核心依旧不认识任何一个具体插件。
      -->
      <button
        v-for="entry in pluginNav"
        :key="entry.pluginId"
        type="button"
        class="nav-item plugin-entry"
        :class="{ active: route.path === entry.to }"
        @click="openPluginEntry(entry.to)"
      >
        <span class="ico" :title="collapsed ? entry.label : undefined">{{ entry.glyph }}</span>
        <span v-if="!collapsed" class="label">{{ entry.label }}</span>
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
  <ConfirmDialog
    :open="confirmDialog.open"
    :title="confirmDialog.title"
    :message="confirmDialog.message"
    confirm-label="删除"
    @cancel="closeConfirmation"
    @confirm="confirmAction"
  />
  <PromptDialog
    :open="groupNameDialog.open"
    :title="groupNameDialog.title"
    :model-value="groupNameDialog.value"
    :label="groupNameDialog.label"
    placeholder="输入分组名称"
    @update:model-value="groupNameDialog.value = $event"
    @cancel="cancelGroupName"
    @confirm="confirmGroupName"
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

/* ---- 分组列表 ---- */
.caret {
  flex: 0 0 auto;
  width: 12px;
  text-align: center;
  font-size: 9px;
  opacity: 0.65;
  cursor: pointer;
}
.root-caret {
  padding: 0 2px;
}
.group-item {
  font-size: 12.5px;
}
/* 「新建分组」：放在「全部笔记」行右侧，悬停时出现 */
.row-add {
  flex: 0 0 auto;
  width: 16px;
  margin-left: auto;
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  opacity: 0;
  cursor: pointer;
}
.nav-item:hover .row-add {
  opacity: 1;
}
.row-add:hover {
  background: var(--c-surface-3);
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
.nav-item.group-item {
  padding-left: calc(var(--space-2) + 22px);
}
.nav-item.group-item.drop-target {
  outline: 1px dashed var(--accent);
  outline-offset: -1px;
  background: var(--accent-soft);
  color: var(--c-text);
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

.sidebar-panel.collapsed {
  width: 46px;
}
.sidebar-panel.collapsed .nav-item {
  justify-content: center;
  padding: 5px 0;
}
</style>
