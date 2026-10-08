import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Toast {
  id: number
  kind: 'info' | 'success' | 'error'
  message: string
}

let toastSeq = 0

/** Shell-level UI state: theme, panels, palette, toasts. */
export const useUiStore = defineStore('ui', () => {
  const theme = ref<'light' | 'dark'>(
    (localStorage.getItem('thisnode.theme') as 'light' | 'dark') || 'light',
  )
  const sidebarCollapsed = ref(localStorage.getItem('thisnode.sidebar') === 'collapsed')
  /**
   * The backlinks panel starts closed: a note's page is the main surface and the panel covers
   * its right third, which would hide (and block) any card organised there. Still a toggle.
   */
  const rightPanelOpen = ref(localStorage.getItem('thisnode.rightPanel') === 'open')
  const toasts = ref<Toast[]>([])
  const busy = ref(false)
  const busyLabel = ref('')

  function applyTheme() {
    document.documentElement.dataset.theme = theme.value
    localStorage.setItem('thisnode.theme', theme.value)
  }

  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    applyTheme()
  }

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem('thisnode.sidebar', sidebarCollapsed.value ? 'collapsed' : 'open')
  }

  function toggleRightPanel() {
    rightPanelOpen.value = !rightPanelOpen.value
    localStorage.setItem('thisnode.rightPanel', rightPanelOpen.value ? 'open' : 'closed')
  }

  function toast(message: string, kind: Toast['kind'] = 'info', ttl = 3200) {
    const id = ++toastSeq
    toasts.value.push({ id, kind, message })
    setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, ttl)
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  function setBusy(value: boolean, label = '') {
    busy.value = value
    busyLabel.value = label
  }

  applyTheme()

  return {
    theme,
    sidebarCollapsed,
    rightPanelOpen,
    toasts,
    busy,
    busyLabel,
    toggleTheme,
    toggleSidebar,
    toggleRightPanel,
    toast,
    dismiss,
    setBusy,
    applyTheme,
  }
})
