import { http } from '@/core/http'
import type { Notebook, OpEntry, SystemInfo, Tag, SyncEvent } from './types'

export const tagApi = {
  list: () => http.get<Tag[]>('/api/tags'),
  rename: (id: string, name: string) => http.put<{ ok: boolean }>(`/api/tags/${id}`, { name }),
}

export const notebookApi = {
  list: () => http.get<Notebook[]>('/api/notebooks'),

  /**
   * 新建分组/笔记本。parentId 就是「树」的由来 —— 后端台账里每个分组带自引用的
   * parentId 与 type（notebook/group）。
   */
  create: (title: string, parentId?: string | null, icon?: string | null) =>
    http.post<Notebook>('/api/notebooks', { title, parentId: parentId ?? null, icon: icon ?? null }),

  /** 改标题/父级/图标/排序 —— 「移动分组」就是把 parentId 换成另一个父级。 */
  update: (id: string, patch: {
    title?: string
    parentId?: string | null
    icon?: string | null
    sortOrder?: number
  }) => http.put<Notebook>(`/api/notebooks/${id}`, patch),

  rename: (id: string, title: string) => http.put<Notebook>(`/api/notebooks/${id}`, { title }),
  remove: (id: string) => http.del<{ ok: boolean }>(`/api/notebooks/${id}`),
  addNode: (id: string, noteId: string) =>
    http.post<{ ok: boolean }>(`/api/notebooks/${id}/nodes`, undefined, { noteId }),
}

export const systemApi = {
  health: () => http.get<Record<string, unknown>>('/api/health'),
  info: () => http.get<SystemInfo>('/api/info'),
  syncStatus: () =>
    http.get<{
      enabled: boolean
      root: string
      events: SyncEvent[]
      orphans: string[]
    }>('/api/notes/sync/status'),
  setSyncEnabled: (value: boolean) =>
    http.post<{ enabled: boolean }>('/api/notes/sync/enabled', undefined, { value }),
  reconcile: () => http.post<{ applied: number; events: SyncEvent[] }>('/api/notes/sync/reconcile'),
  reindex: () =>
    http.post<{ links: number; mirrored: number; tookMs: number }>('/api/maintenance/reindex'),
  stats: () => http.get<Record<string, unknown>>('/api/maintenance/stats'),
}

export const ioApi = {
  mirrorRewrite: () =>
    http.post<{ rewritten: number; root: string }>('/api/io/mirror/rewrite'),
  mirrorInfo: () =>
    http.get<{ root: string; attachmentRoot: string; orphanFiles: number }>('/api/io/mirror/info'),
}

export const attachmentApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<{ name: string; url: string; size: number }>('/api/attachments', form)
  },
  url: (relative: string) => http.url(`/api/attachments/${relative.replace(/^\/+/, '')}`),
}
