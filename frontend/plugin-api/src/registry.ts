import { ref, shallowReactive, type Component } from 'vue'
import type { CardPlugin, NoteBatchAction, NoteListFilter, NoteListHeader, NoteRowAction, NoteToolPlugin, PluginNavEntry, PluginRoute, SidebarPanel, PluginSettingsSection, TopbarSearch } from './types'

/**
 * 插件注册器：插件模块在被 import 时调用 register* 完成自注册
 * （见 index.ts），NoteBlock / 设置页 / 侧栏 / 笔记头 / 路由只面向本注册器渲染，
 * 不认识任何具体插件。
 *
 * 插槽覆盖：卡片工具、笔记工具、设置面板、侧栏入口、插件自有路由、列表筛选、列表行内动作、
 * 列表批量动作等（完整清单见 project.md §6.4）。
 *
 * <p>这些集合是**响应式**的：插件界面在运行时才装载（见 loader.ts），
 * 晚到的注册必须能让已经渲染好的槽位重新渲染。
 */
const plugins = shallowReactive<CardPlugin[]>([])
const settingsSections = shallowReactive(new Map<string, Component>())
const navEntries = shallowReactive<PluginNavEntry[]>([])
const tools = shallowReactive<NoteToolPlugin[]>([])
const routes = shallowReactive<PluginRoute[]>([])
const listFilters = shallowReactive<NoteListFilter[]>([])
const sidebarPanels = shallowReactive(new Map<string, SidebarPanel>())
const listHeaders = shallowReactive<NoteListHeader[]>([])
const topbarSearches = shallowReactive<TopbarSearch[]>([])
const rowActions = shallowReactive<NoteRowAction[]>([])
const batchActions = shallowReactive<NoteBatchAction[]>([])

/**
 * 当前激活的插件列表筛选（共享指针）：侧栏行或插件自己的 UI（如搜索框）都通过
 * setActiveListFilter/clearActiveListFilter 改它；笔记域 watch 它并调用筛选的 load()。
 * 指针放注册器而非笔记域 —— 激活方（任意微应用）与消费方（notes）不必互相认识。
 */
const activeListFilter = ref<{ pluginId: string; filterId: string } | null>(null)

export function setActiveListFilter(pluginId: string, filterId: string): void {
  activeListFilter.value = { pluginId, filterId }
}

export function clearActiveListFilter(): void {
  activeListFilter.value = null
}

export function activeListFilter_(): { pluginId: string; filterId: string } | null {
  return activeListFilter.value
}

/** 登记列表页头部组件（渲染在列表行上方；按注册顺序）。 */
export function registerListHeader(header: NoteListHeader): void {
  if (listHeaders.some((h) => h.pluginId === header.pluginId)) return
  listHeaders.push(header)
}

export function listHeaders_(): NoteListHeader[] {
  return listHeaders
}

/** 登记顶栏搜索组件（渲染在 logo 右侧；同一插件只认第一次）。 */
export function registerTopbarSearch(item: TopbarSearch): void {
  if (topbarSearches.some((t) => t.pluginId === item.pluginId)) return
  topbarSearches.push(item)
}

export function topbarSearches_(): TopbarSearch[] {
  return topbarSearches
}

export function registerCardPlugin(plugin: CardPlugin): void {
  const existing = plugins.findIndex((p) => p.id === plugin.id)
  if (existing >= 0) {
    // Replace the old callbacks/component when a plugin module is hot-reloaded.
    plugins.splice(existing, 1, plugin)
    return
  }
  plugins.push(plugin)
}

export function cardPlugins(): CardPlugin[] {
  return plugins
}

/** 服务插件登记自己的设置面板；同一个 id 只认第一次注册。 */
export function registerPluginSettings(section: PluginSettingsSection): void {
  if (settingsSections.has(section.pluginId)) return
  settingsSections.set(section.pluginId, section.component)
}

/** 取某个插件的设置面板；没有贡献则返回 undefined，由管理页回退到通用配置表单。 */
export function pluginSettings(pluginId: string): Component | undefined {
  return settingsSections.get(pluginId)
}

/** 插件登记侧栏入口。 */
export function registerNavEntry(entry: PluginNavEntry): void {
  if (navEntries.some((e) => e.pluginId === entry.pluginId)) return
  navEntries.push(entry)
}

/** 侧栏要渲染的所有插件入口（保持注册顺序）。 */
export function navEntries_(): PluginNavEntry[] {
  return navEntries
}

/** 插件登记「笔记工具」（挂在笔记标题旁的实体开关，例如 ★ 收藏）。 */
export function registerNoteTool(tool: NoteToolPlugin): void {
  if (tools.some((t) => t.pluginId === tool.pluginId)) return
  tools.push(tool)
}

export function noteTools(): NoteToolPlugin[] {
  return tools
}

/** 插件登记列表行内动作（每行右侧的图标，例如 ★ 收藏）。一个插件一个。 */
export function registerNoteRowAction(action: NoteRowAction): void {
  const existing = rowActions.findIndex((item) => item.pluginId === action.pluginId)
  if (existing >= 0) {
    // 热更新时替换旧回调
    rowActions.splice(existing, 1, action)
    return
  }
  rowActions.push(action)
}

export function noteRowActions(): NoteRowAction[] {
  return rowActions
}

/** 插件登记列表批量动作（批量操作条上的按钮）。同一插件可按 label 注册多个。 */
export function registerNoteBatchAction(action: NoteBatchAction): void {
  const existing = batchActions.findIndex(
    (item) => item.pluginId === action.pluginId && item.label === action.label,
  )
  if (existing >= 0) {
    batchActions.splice(existing, 1, action)
    return
  }
  batchActions.push(action)
}

export function noteBatchActions(): NoteBatchAction[] {
  return batchActions
}

/** 插件登记自己的页面路由；路由创建后由 router 统一挂到 Shell 下。 */
export function registerPluginRoute(route: PluginRoute): void {
  if (routes.some((r) => r.name === route.name)) return
  routes.push(route)
}

export function pluginRoutes(): PluginRoute[] {
  return routes
}

/**
 * 并发取指定入口的徽标数量；某个插件失败不影响其他入口。
 *
 * <p>入口由调用方传入（侧栏只传仍在运行的插件）：注册器不认识「启用状态」，那份状态在
 * {@code availability} 里，而后者依赖本模块——互相 import 会成环，所以用参数把过滤后的列表递进来。
 *
 * <p><b>为什么带缓存</b>：侧栏在「进应用、切路由、窗口获得焦点」三个时机都会刷新徽标
 * （见 AppSidebar），而徽标对每个插件要打一次业务请求（复习的 due、收藏的列表）。没有缓存时
 * 「进应用 + 首次路由跳转」就会把同一份徽标请求打两到四遍。这里与 availability 一致，用
 * 「在飞合并 + 极短新鲜期」把同一波刷新并成一次；窗口重新聚焦导致的重复只是界面状态，不需要更实时。
 */
const NAV_BADGE_FRESH_MS = 3_000
let navBadgesCache: { at: number; value: Record<string, number> } | null = null
let navBadgesInflight: Promise<Record<string, number>> | null = null

export async function navBadges(entries: PluginNavEntry[], force = false): Promise<Record<string, number>> {
  if (!force && navBadgesCache && Date.now() - navBadgesCache.at < NAV_BADGE_FRESH_MS) {
    return navBadgesCache.value
  }
  if (navBadgesInflight) {
    return navBadgesInflight
  }
  navBadgesInflight = (async () => {
    const out: Record<string, number> = {}
    await Promise.all(entries.map(async (entry) => {
      if (!entry.badge) return
      try {
        const n = await entry.badge()
        if (n && n > 0) out[entry.pluginId] = n
      } catch {
        // 徽标拿不到就不显示，不打扰用户
      }
    }))
    navBadgesCache = { at: Date.now(), value: out }
    navBadgesInflight = null
    return out
  })()
  return navBadgesInflight
}
/** 插件登记一个列表筛选（例如「收藏」）。同一个 id 只认第一次注册。 */
export function registerNoteListFilter(filter: NoteListFilter): void {
  if (listFilters.some((f) => f.id === filter.id)) return
  listFilters.push(filter)
}

/** 所有插件提供的列表筛选（响应式：插件运行时装载后才登记也能被渲染）。 */
export function noteListFilters(): NoteListFilter[] {
  return listFilters
}

/** 业务域登记侧栏主面板（笔记域登记列表面板；同一插件只认第一次）。 */
export function registerSidebarPanel(panel: SidebarPanel): void {
  if (sidebarPanels.has(panel.pluginId)) return
  sidebarPanels.set(panel.pluginId, panel)
}

/** 当前已登记的侧栏面板（宿主侧栏容器按它渲染）。 */
export function sidebarPanels_(): SidebarPanel[] {
  return [...sidebarPanels.values()]
}

/**
 * 注销一个插件在所有插槽上的注册（qiankun 微应用 unmount 时调用）。
 *
 * <p>旧机制里插件界面被 import 一次就永远留在注册表里，"停用"只能靠可用性桥把 UI 藏起来；
 * 改成 qiankun 微应用后，unmount 是真正的生命周期终点：宿主调 {@code unload}，
 * 插件的 {@code unmount()} 回调里调这里，六个插槽的注册一起撤走，
 * 随后再 mount（启用 / 重载）时重新注册即可，无需刷新页面。
 */
export function unregisterPlugin(pluginId: string): void {
  for (let i = plugins.length - 1; i >= 0; i--) {
    if (plugins[i].id === pluginId) plugins.splice(i, 1)
  }
  settingsSections.delete(pluginId)
  for (let i = navEntries.length - 1; i >= 0; i--) {
    if (navEntries[i].pluginId === pluginId) navEntries.splice(i, 1)
  }
  for (let i = tools.length - 1; i >= 0; i--) {
    if (tools[i].pluginId === pluginId) tools.splice(i, 1)
  }
  for (let i = routes.length - 1; i >= 0; i--) {
    if (routes[i].pluginId === pluginId) routes.splice(i, 1)
  }
  for (let i = listFilters.length - 1; i >= 0; i--) {
    if (listFilters[i].pluginId === pluginId) listFilters.splice(i, 1)
  }
  sidebarPanels.delete(pluginId)
  for (let i = listHeaders.length - 1; i >= 0; i--) {
    if (listHeaders[i].pluginId === pluginId) listHeaders.splice(i, 1)
  }
  for (let i = topbarSearches.length - 1; i >= 0; i--) {
    if (topbarSearches[i].pluginId === pluginId) topbarSearches.splice(i, 1)
  }
  for (let i = rowActions.length - 1; i >= 0; i--) {
    if (rowActions[i].pluginId === pluginId) rowActions.splice(i, 1)
  }
  for (let i = batchActions.length - 1; i >= 0; i--) {
    if (batchActions[i].pluginId === pluginId) batchActions.splice(i, 1)
  }
  if (activeListFilter.value?.pluginId === pluginId) activeListFilter.value = null
}

export function getNoteListFilter(id: string): NoteListFilter | undefined {
  return listFilters.find((f) => f.id === id)
}
