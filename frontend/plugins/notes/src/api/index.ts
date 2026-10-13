import { http } from '@thisnode/plugin-api'
import type { Notebook } from './types'

/** Notes-domain clients. */

export const notebookApi = {
  list: () => http.get<Notebook[]>('/api/notebooks/get'),

  /** 新建分组时始终创建在顶层。 */
  create: (title: string, icon?: string | null) =>
    http.post<Notebook>('/api/notebooks/create', { title, icon: icon ?? null }),

  /** 更新分组的标题、图标或排序。 */
  update: (id: string, patch: {
    title?: string
    icon?: string | null
    sortOrder?: number | null
  }) => http.post<Notebook>('/api/notebooks/update', patch, { id }),

  remove: (id: string) => http.post<{ ok: boolean }>('/api/notebooks/delete', undefined, { id }),
}
