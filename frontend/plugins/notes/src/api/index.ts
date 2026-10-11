import { http } from '@/core/http'
import type { Notebook } from './types'

/** Notes-domain clients: notebooks and attachment upload. */

export const notebookApi = {
  list: () => http.get<Notebook[]>('/api/notebooks'),

  /** 新建分组时始终创建在顶层。 */
  create: (title: string, icon?: string | null) =>
    http.post<Notebook>('/api/notebooks/create', { title, icon: icon ?? null }),

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

export const attachmentApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<{ name: string; url: string; size: number }>('/api/attachments/upload', form)
  },
  url: (relative: string) => http.url(`/api/attachments/${relative.replace(/^\/+/, '')}`),
}
