import { reactive } from 'vue'

/**
 * 宿主外壳的跨微应用共享状态（产物分离后，笔记域是 notes 微应用，
 * 主题/toast 这些「壳级 UI 状态」必须让宿主与各微应用看到同一份）。
 *
 * <p>放在 @thisnote/plugin-api 里走共享依赖注入 —— 与 vue 同一机制：
 * 宿主与微应用拿到的是**同一份模块实例**，reactive 状态天然共享。
 * 这不是 Pinia store：注册表与可用性桥已经确立了「模块级 reactive 单例」的模式，
 * 这里沿用（微应用不自带 Pinia 实例）。
 */

export interface Toast {
  id: number
  kind: 'info' | 'success' | 'error'
  message: string
}

let toastSeq = 0

const state = reactive({
  /** 宿主主题（顶栏切换，状态栏显示）。 */
  theme: (localStorage.getItem('thisnote.theme') as 'light' | 'dark') || 'light',
  /** 全局 toast 队列；宿主 ToastHost 渲染，任何微应用都能发。 */
  toasts: [] as Toast[],
  /**
   * 侧栏收起（共享：宿主壳控制宽度，notes 侧栏面板据此切换 icon-only 形态）。
   * 收起后左侧保留一列小图标，而不是整条侧栏消失。
   */
  sidebarCollapsed: localStorage.getItem('thisnote.sidebar') === 'collapsed',
})

function applyTheme() {
  document.documentElement.dataset.theme = state.theme
  localStorage.setItem('thisnote.theme', state.theme)
}
applyTheme()

export function toggleTheme() {
  state.theme = state.theme === 'light' ? 'dark' : 'light'
  applyTheme()
}

export function toggleSidebar() {
  state.sidebarCollapsed = !state.sidebarCollapsed
  localStorage.setItem('thisnote.sidebar', state.sidebarCollapsed ? 'collapsed' : 'open')
}

export function toast(message: string, kind: Toast['kind'] = 'info', ttl = 3200) {
  const id = ++toastSeq
  state.toasts.push({ id, kind, message })
  setTimeout(() => dismiss(id), ttl)
}

export function dismiss(id: number) {
  const i = state.toasts.findIndex((t) => t.id === id)
  if (i >= 0) state.toasts.splice(i, 1)
}

export const shellState = state
