/** Small shared helpers. */

export function debounce<T extends (...args: never[]) => void>(fn: T, wait: number) {
  let timer: number | null = null
  return (...args: Parameters<T>) => {
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(() => fn(...args), wait)
  }
}

export function formatDate(ts: number): string {
  if (!ts) return '—'
  const d = new Date(ts)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) {
    return `今天 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  const yesterday = new Date(now.getTime() - 86400000)
  if (d.toDateString() === yesterday.toDateString()) return '昨天'
  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}月${d.getDate()}日`
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`
}

export function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatRelative(ts: number): string {
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

export function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** True when the platform's primary modifier is pressed (Cmd on macOS, Ctrl elsewhere). */
export function isPrimaryModifier(e: KeyboardEvent | MouseEvent): boolean {
  return navigator.platform.toLowerCase().includes('mac') ? e.metaKey : e.ctrlKey
}

export function primaryLabel(): string {
  return navigator.platform.toLowerCase().includes('mac') ? '⌘' : 'Ctrl'
}

export function nextTickFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

/** Blur-safe text selection inside an input. */
export function selectAllText(el: HTMLInputElement | HTMLTextAreaElement) {
  el.focus()
  el.select()
}
