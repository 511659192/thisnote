import { reactive } from 'vue'
import { pluginApi } from './pluginApi'
import type { CardPlugin, NoteBatchAction, NoteListFilter, NoteRowAction, NoteToolPlugin, PluginNavEntry } from './types'
import { cardPlugins, navEntries_, noteBatchActions, noteListFilters, noteRowActions, noteTools } from './registry'

/**
 * 运行时的插件可用性。
 *
 * <p><b>为什么需要这个文件：</b>界面侧的插件注册是<b>编译期</b>的（import 即注册），而后端的
 * 「停用」是<b>运行时</b>的。两者之间原本没有桥，于是停用插件后：卡片上的 🧠 徽标和侧栏的
 * 「复习」入口照旧显示，点下去只换来一串静默的 409 —— 停用没有闭环。
 *
 * <p>这里把后端的 active 状态拉成一份响应式快照，三个插槽（卡片工具 / 侧栏入口 / 复习页）
 * 都据此过滤。取不到时按「全部可用」处理：一次网络抖动不该把功能藏起来。
 */
const state = reactive({ inactive: new Set<string>() })

export function isPluginActive(pluginId: string): boolean {
  return !state.inactive.has(pluginId)
}

/**
 * 拉一次插件启用状态；应用启动、窗口焦点/变为可见时调用；管理页的启停与重载传 `force`。
 *
 * <p><b>去重</b>：用「在飞请求 + 新鲜期」合并 —— 焦点抖动（比如切出去看一眼 DevTools 再
 * 切回来）在新鲜期内不再打请求，普通点笔记永远是零请求；`force` 跳过新鲜期，给
 * 「改完状态必须立刻生效」的管理页用。
 */
let inflight: Promise<void> | null = null
let lastLoadedAt = 0
const FRESH_MS = 30_000

export async function refreshPluginAvailability(force = false): Promise<void> {
  if (inflight) {
    return inflight
  }
  if (!force && Date.now() - lastLoadedAt < FRESH_MS) {
    return
  }
  inflight = (async () => {
    try {
      const plugins = await pluginApi.list()
      const next = new Set<string>()
      for (const plugin of plugins) {
        if (!plugin.active) next.add(plugin.id)
      }
      state.inactive = next
    } catch {
      state.inactive = new Set()
    } finally {
      inflight = null
      lastLoadedAt = Date.now()
    }
  })()
  return inflight
}

/** 只保留仍在运行的插件的卡片工具。 */
export function activeCardPlugins(): CardPlugin[] {
  return cardPlugins().filter((plugin) => isPluginActive(plugin.id))
}

/** 只保留仍在运行的插件的侧栏入口；order 小者在上，未给序号的按注册顺序排在其后。 */
export function activeNavEntries(): PluginNavEntry[] {
  return navEntries_()
    .filter((entry) => isPluginActive(entry.pluginId))
    .sort((a, b) => (a.order ?? Number.MAX_SAFE_INTEGER) - (b.order ?? Number.MAX_SAFE_INTEGER))
}

/** 只保留仍在运行的插件的笔记工具。 */
export function activeNoteTools(): NoteToolPlugin[] {
  return noteTools()
    .filter((tool) => isPluginActive(tool.pluginId))
    .sort((a, b) => (a.order ?? Number.MAX_SAFE_INTEGER) - (b.order ?? Number.MAX_SAFE_INTEGER))
}
/** 仍在运行的插件提供的列表筛选（例如「收藏」）；插件停用即从侧栏消失。 */
export function activeNoteListFilters(): NoteListFilter[] {
  return noteListFilters().filter((filter) => isPluginActive(filter.pluginId))
}

/** 只保留仍在运行的插件登记的列表行内动作。 */
export function activeNoteRowActions(): NoteRowAction[] {
  return noteRowActions().filter((action) => isPluginActive(action.pluginId))
}

/** 只保留仍在运行的插件登记的列表批量动作。 */
export function activeNoteBatchActions(): NoteBatchAction[] {
  return noteBatchActions().filter((action) => isPluginActive(action.pluginId))
}
