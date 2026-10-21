/**
 * 核心笔记标题变化通知。
 *
 * <p>插件通过自己的后端能力更新笔记标题后广播；笔记页据此同步标题，不依赖具体插件。
 */
export interface NoteTitleChange {
  noteId: string
  title: string
}

type NoteTitleListener = (change: NoteTitleChange) => void

const listeners = new Set<NoteTitleListener>()

export function onNoteTitleChange(listener: NoteTitleListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function emitNoteTitleChange(change: NoteTitleChange): void {
  for (const listener of [...listeners]) {
    try {
      listener(change)
    } catch {
      // 单个订阅方异常不应打断插件自己的流程
    }
  }
}
