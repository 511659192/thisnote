import { http } from '@thisnote/plugin-api'
import type { Book } from './types'

/** Notes-domain clients. */

export const bookApi = {
  list: () => http.get<Book[]>('/api/books/get'),

  /** 新建分组时始终创建在顶层。 */
  create: (title: string, icon?: string | null) =>
    http.post<Book>('/api/books/create', { title, icon: icon ?? null }),

  /** 更新分组的标题、图标或排序。 */
  update: (id: string, patch: {
    title?: string
    icon?: string | null
    sortOrder?: number | null
  }) => http.post<Book>('/api/books/update', patch, { id }),

  remove: (id: string) => http.post<{ ok: boolean }>('/api/books/delete', undefined, { id }),
}
