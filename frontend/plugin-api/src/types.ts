import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 插件底座的界面侧契约：一个「卡片插件」向卡片右上角的工具行贡献一个开关按钮，
 * 并可挂载自己的状态徽标/交互组件。
 *
 * 状态的读写由插件自己的 API 与存储负责；宿主只负责装载、渲染与可用性管理。
 */
export interface CardPlugin {
  /** 稳定 id，须与插件 jar 的 plugin.properties 里 plugin.id 一致 */
  id: string
  /** 工具行提示与卡片右键菜单中使用的功能名称 */
  title: string
  /** 工具行按钮的字形 */
  glyph: string

  /** 一个笔记页打开时批量装载各卡片的状态（每页一次请求，而非每卡片一次） */
  loadState(cardIds: string[]): Promise<void>

  isEnabled(cardId: string): boolean

  /** 开启/关闭；由插件自己的 API 持久化状态。 */
  toggle(cardId: string): Promise<void>

  /** 可选：为已启用该插件的卡片挂载组件（徽标、评分栏等） */
  cardComponent?: Component
}

/**
 * 服务插件贡献给「插件管理」页的设置面板。
 *
 * <p>设置页本身不认识任何具体插件：它按 {@code pluginId} 到这里取组件。百度网盘备份的
 * 授权/备份/恢复界面就是这么挂上去的——插件把自己的 UI 一起带来，底座只提供插槽。
 */
export interface PluginSettingsSection {
  pluginId: string
  component: Component
}

/**
 * 插件贡献的侧栏入口。
 *
 * <p>侧栏原本硬编码了一个「🧠 复习」按钮（为了记忆曲线），那等于让核心认识具体插件。
 * 现在改成插槽：插件登记自己的入口与徽标数量，侧栏只管渲染。
 */
export interface PluginNavEntry {
  pluginId: string
  label: string
  glyph: string
  /** 目标路由，例如 {@code /review} */
  to: string
  /** 徽标数量；返回 0 或 null 则不显示徽标 */
  badge?: () => Promise<number | null>
  /** 侧栏排序：小者在上；未给序号的按注册顺序排在已给序号的入口之后 */
  order?: number
}

/**
 * 插件贡献的「笔记工具」：挂在笔记标题旁的按实体开关（收藏就是这一类）。
 *
 * <p>与卡片工具的区别只在作用对象：一个挂在卡片上，一个挂在笔记上。宿主不关心插件具体是谁，
 * 实体状态归插件管理，宿主只负责按可用性把它渲染出来。
 */
export interface NoteToolPlugin {
  pluginId: string
  /** 提示文字（无状态时的说法） */
  title: string
  glyph: string
  /** 组件接收 noteId prop */
  component: Component
  /** 挂载位置：默认在标题右侧；'below-title' 渲染为标题下方的独立区域。 */
  placement?: 'title' | 'below-title'
  /** 同一挂载位置的排序：小者在前；未给序号的按注册顺序排在已给序号之后。 */
  order?: number
}

/**
 * 插件贡献的路由（插件自有页面，例如「收藏」列表页）。
 *
 * <p>核心的路由表里不会出现任何具体插件的路径：插件自己登记，路由创建后统一挂到 Shell 下。
 */
export interface PluginRoute {
  pluginId: string
  path: string
  name: string
  /** 复用 vue-router 的类型：既接受组件，也接受 {@code () => import(...)} 懒加载 */
  component: RouteRecordRaw['component']
  /** 透传给 vue-router 的 props（如 {@code /notes/:id} 的 {@code props: true}）。 */
  props?: boolean
  /** 标识顶栏“使用指南”入口对应的插件页面；只应有一个插件声明。 */
  onboarding?: boolean
}

/**
 * 侧栏主面板插槽：整个侧栏的内容区由各业务域登记（笔记域登记列表面板，
 * 宿主自己只渲染插件 nav 轨与面板容器）—— 前后端的模块边界由此对齐。
 */
export interface SidebarPanel {
  pluginId: string
  component: Component
}

/**
 * 插件提供的**笔记列表筛选**（第六个插槽）。
 *
 * <p>这是"收藏"这类功能的正确形态：它不拥有页面与布局，只是给核心那份列表加一个条件。
 * 插件回答"哪些 id 命中"，剩下的列表渲染、点开笔记、内容展示全部由核心负责 ——
 * 于是它与「全部笔记/最近编辑」天然同构，不存在两套布局漂移的问题。
 */
export interface NoteListFilter {
  /** 贡献它的插件 id（停用即整体消失）。 */
  pluginId: string
  /** 稳定标识，例如 `favorites`。 */
  id: string
  /** 侧栏筛选组里显示的名字。 */
  label: string
  /** 可选图标。 */
  icon?: string
  /**
   * 不在侧栏渲染筛选行：筛选由插件自己的 UI（如列表头部的搜索框）激活。
   * 缺省 false —— 像收藏那样由侧栏行点选的筛选不需要设置它。
   */
  hidden?: boolean
  /** 返回命中的笔记 id（核心据此过滤自己的列表）。 */
  load(): Promise<string[]>
}

/**
 * 列表页头部插槽：渲染在笔记列表行的上方（列表页上部）。
 * 典型用途：搜索框、批量操作条等「作用于这份列表」的插件 UI。
 */
export interface NoteListHeader {
  pluginId: string
  component: Component
}

/**
 * 顶栏搜索插槽：渲染在 logo 右侧的顶栏区。典型用途：全局模糊查询
 * （跨标题与摘要，下拉结果可直接跳转）。
 */
export interface TopbarSearch {
  pluginId: string
  component: Component
}

/**
 * 笔记列表动作里用到的笔记引用（核心 DTO 的子集）。
 *
 * <p>插件不应该依赖核心的 {@code NoteDto}：它只需要 id、标题与卡片 id。
 */
export interface NoteRef {
  noteId: string
  title: string
  cardIds: string[]
}

/**
 * 笔记列表的「行内动作」（第九个插槽）：渲染在每行右侧的小图标，例如收藏、记忆曲线。
 *
 * <p>状态与读写全部归插件自己 —— 核心只把笔记引用递进来，按注册表渲染。
 */
export interface NoteRowAction {
  pluginId: string
  /** 自定义渲染（优先于 glyph）：组件接收 `note: NoteRef`，自己处理点击与状态。 */
  component?: Component
  glyph?: string
  /** 激活态字形（可选，例如 ☆ → ★）。 */
  activeGlyph?: string
  /** 随状态变化的提示文字。 */
  title?(note: NoteRef): string
  isActive?(note: NoteRef): boolean
  /** 列表变化后批量装载状态（可选）。 */
  loadState?(notes: NoteRef[]): Promise<void>
  toggle?(note: NoteRef): Promise<void>
}

/**
 * 笔记列表的「批量动作」（第十个插槽）：渲染在批量操作条上，作用于所选笔记。
 *
 * <p>同一插件可以注册多个（例如「收藏 +」与「收藏 −」），因此以 label 区分。
 */
export interface NoteBatchAction {
  pluginId: string
  label: string
  title?: string
  /** 执行后返回自定义提示（例如按卡片计数）；不返回则用默认的「N 篇笔记」。 */
  run(notes: NoteRef[]): Promise<void | string>
}
/**
 * qiankun 微前端的全局标记（宿主在创建沙箱时装上）。
 *
 * 插件入口据此区分「被宿主以 qiankun 微应用装载」与「独立运行」两种形态：
 * 前者把注册动作放进 mount() 生命周期（可随 unmount 撤销），后者 import 时直接注册。
 */
declare global {
  interface Window {
    __POWERED_BY_QIANKUN__?: boolean
  }
}

export {}
