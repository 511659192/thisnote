import { computed } from 'vue'
import { defineStore } from 'pinia'
import {
  shellState,
  toast as shellToast,
  toggleSidebar as shellToggleSidebar,
  toggleTheme as shellToggleTheme,
} from '@thisnode/plugin-api'

/**
 * 宿主壳级 UI 状态。
 *
 * 产物分离后，theme / toast / 侧栏收起是**跨微应用共享**的壳级状态，
 * 真身放在 {@link shellState}（@thisnode/plugin-api，模块级 reactive 单例）——
 * notes 微应用与宿主看到的是同一份。这里把共享状态以 Pinia 计算属性的形式
 * 代理出去，老调用点（ui.toggleSidebar(...)）无需改动。
 */
export const useUiStore = defineStore('ui', () => {
  return {
    // —— 共享壳状态（真身在 @thisnode/plugin-api/shell-state）——
    theme: computed(() => shellState.theme),
    sidebarCollapsed: computed(() => shellState.sidebarCollapsed),
    toggleTheme: shellToggleTheme,
    toggleSidebar: shellToggleSidebar,
    toast: shellToast,
  }
})
