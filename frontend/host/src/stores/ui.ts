import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  dismiss as shellDismiss,
  setBusy as shellSetBusy,
  shellState,
  toast as shellToast,
  toggleTheme as shellToggleTheme,
} from '@thisnode/plugin-api'

/**
 * 宿主壳级 UI 状态。
 *
 * 产物分离后，theme / toast / busy 是**跨微应用共享**的壳级状态，
 * 真身放在 {@link shellState}（@thisnode/plugin-api，模块级 reactive 单例）——
 * notes 微应用与宿主看到的是同一份。这里只保留宿主独有的侧栏折叠，
 * 并把共享状态以 Pinia 计算属性的形式代理出去，老调用点（ui.toast(...)）无需改动。
 */
export const useUiStore = defineStore('ui', () => {
  const sidebarCollapsed = ref(localStorage.getItem('thisnode.sidebar') === 'collapsed')

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem('thisnode.sidebar', sidebarCollapsed.value ? 'collapsed' : 'open')
  }

  return {
    // —— 共享壳状态（真身在 @thisnode/plugin-api/shell-state）——
    theme: computed(() => shellState.theme),
    toasts: computed(() => shellState.toasts),
    busy: computed(() => shellState.busy),
    busyLabel: computed(() => shellState.busyLabel),
    toggleTheme: shellToggleTheme,
    toast: shellToast,
    dismiss: shellDismiss,
    setBusy: shellSetBusy,
    // —— 宿主独有 ——
    sidebarCollapsed,
    toggleSidebar,
  }
})
