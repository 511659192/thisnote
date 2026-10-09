<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { useUiStore } from '@/stores/ui'
import { notebookApi } from '@/api'
import '@/plugins'
import { navBadges } from '@/plugins'
import { activeNavEntries, refreshPluginAvailability } from '@/plugins'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { ListFilter, Notebook } from '@/api/types'
import { activeNoteListFilters, type NoteListFilter } from '@/plugins'

const ui = useUiStore()
const nodes = useNoteStore()
const router = useRouter()
const route = useRoute()

/**
 * 侧栏的插件入口（原来是硬编码的「复习」按钮 —— 那等于让核心认识记忆曲线插件）。
 * 现在只面向注册器：入口由插件登记，徽标数量由插件自己算，核心一个具体插件都不认识。
 */
const pluginNav = computed(() => activeNavEntries())

/**
 * 当前是否停在某个插件页面上。
 *
 * <p>用来让核心视图不再显示为选中：插件入口与「全部笔记/最近编辑」同级，同一时刻只该有一行是亮的。
 */
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
 *   启停/重载之后拉。qiankun 生命周期之后，本窗口内的启停会直接装卸微应用并注销插槽
 *   （SettingsView → loader → unregisterPlugin），注册表本身就是本窗口的权威；
 *   跨窗口 / 脚本 / 后端侧的外部变化，由「切回窗口」的焦点事件兜底 —— 那是这类改动
 *   真正发生的时刻。以前切路由也拉一次，代价是每点开一条笔记就打一遍 /api/plugins。
 * - <b>徽标</b>：切路由时刷新（从复习页回来，due 数就该变），有 3 秒在飞合并兜底，
 *   且只碰各插件自己的徽标端点。
 */
watch(() => route.fullPath, () => void refreshPluginBadges())
onMounted(() => {
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

/**
 * 侧栏只保留常用视图。
 *
 * <p>「每日笔记 / 模板 / 回收站」已从侧栏移除（界面做减法）；底层能力都还在，
 * 回收站当前没有界面入口（软删数据仍完整，恢复走 API）。归档功能已整体移除。
 */
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
  open: false, x: 0, y: 0, entries: [],
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

onMounted(async () => {
  if (nodes.notebooks.length === 0) void nodes.refreshMeta()
  // 先拿插件的启用状态，再取徽标——被停用的插件不该露出入口
  await refreshPlugins()
})
</script>

<template>
  <aside class="sidebar">
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
          :class="{ active: !isPluginPage && nodes.filter === f.key && !nodes.activeTag && !nodes.activeNotebookId }"
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
          :class="{ active: !isPluginPage && nodes.pluginFilter?.filterId === pf.id }"
          @click="openPluginFilter(pf)"
        >
          <span class="ico">{{ pf.icon || '★' }}</span>
          <span class="label">{{ pf.label }}</span>
        </button>

      <!--
        插件贡献的入口（复习 / 收藏…）与上面的核心视图**同一层级**：同样的行、同样的选中态、
        同样的计数位。核心依旧不认识任何一个具体插件——它只是把注册器里的入口渲染出来。
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
.sidebar {
  width: var(--sidebar-w);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border-right: 1px solid var(--c-border);
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
/* 「全部笔记」右侧的总开关：收起时侧栏只剩核心视图 */
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
/* 拖拽悬停：让落点一眼可见（笔记拖进来 = 移到这个分组；分组拖进来 = 变成子分组） */
.group-item.drop-target {
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  outline: 1px dashed color-mix(in srgb, var(--accent) 60%, transparent);
  outline-offset: -2px;
}
/* 图标即按钮：平时显示原图标，鼠标移到这一行时换成 ＋（新建分组/子分组）。
   这样「新建分组」不用额外占一行，就长在「全部笔记」的图标上。 */
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
.mini {
  margin-left: auto;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  color: var(--c-text-3);
  font-size: 13px;
  line-height: 1;
}
.mini:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
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
