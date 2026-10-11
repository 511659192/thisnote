/**
 * 搬迁兼容层：笔记域组件historically经 `@/stores/ui` 使用 toast 与主题。
 * 笔记域成为微应用后，这些是跨微应用共享的壳级状态 —— 真身在
 * `@thisnode/plugin-api` 的 shell-state（沙箱注入，同一份实例）。
 * 这里以同名 `useUiStore()` 转发，搬迁组件无需逐处改 import。
 */
import { shellState, toast as shellToast, toggleTheme as shellToggleTheme } from '@thisnode/plugin-api'

export function useUiStore() {
  return {
    get theme() {
      return shellState.theme
    },
    get toasts() {
      return shellState.toasts
    },
    get busy() {
      return shellState.busy
    },
    get busyLabel() {
      return shellState.busyLabel
    },
    toggleTheme: shellToggleTheme,
    toast: shellToast,
  }
}
