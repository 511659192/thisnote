import { http } from '@/core/http'
import type { Notebook, OpEntry, Tag } from './types'

export const tagApi = {
  list: () => http.get<Tag[]>('/api/tags'),
  rename: (id: string, name: string) =>
    http.post<{ ok: boolean }>('/api/tags/update', { name }, { id }),
}

export const notebookApi = {
  list: () => http.get<Notebook[]>('/api/notebooks'),

  /**
   * 新建分组/笔记本。parentId 就是「树」的由来 —— 后端台账里每个分组带自引用的
   * parentId 与 type（notebook/group）。
   */
  create: (title: string, parentId?: string | null, icon?: string | null) =>
    http.post<Notebook>('/api/notebooks', { title, parentId: parentId ?? null, icon: icon ?? null }),

  /** 全量替换：改标题/父级/图标/排序，四个字段都以本次请求为准（parentId=null 即移到顶层）。 */
  update: (id: string, patch: {
    title?: string
    parentId?: string | null
    icon?: string | null
    sortOrder?: number | null
  }) => http.post<Notebook>('/api/notebooks/update', patch, { id }),

  remove: (id: string) => http.post<{ ok: boolean }>('/api/notebooks/delete', undefined, { id }),
  addNode: (id: string, noteId: string) =>
    http.post<{ ok: boolean }>('/api/notebooks/nodes', undefined, { id, noteId }),
}

export const systemApi = {
  reindex: () =>
    http.post<{ links: number; mirrored: number; tookMs: number }>('/api/maintenance/reindex'),
}

export const attachmentApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<{ name: string; url: string; size: number }>('/api/attachments', form)
  },
  url: (relative: string) => http.url(`/api/attachments/${relative.replace(/^\/+/, '')}`),
}
