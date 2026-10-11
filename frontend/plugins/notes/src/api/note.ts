import { http } from '@thisnode/plugin-api'
import type { NoteDetail, NoteMeta } from './types'

export interface NoteQuery {
  notebookId?: string
  type?: string
  text?: string
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteMeta[]>('/api/notes', query as never),
  get: (id: string) => http.get<NoteDetail>('/api/notes/detail', { id }),

  create: (payload: {
    title?: string
    contentMd?: string
    type?: string
    notebookId?: string
  }) => http.post<NoteDetail>('/api/notes/create', payload),

  update: (
    id: string,
    payload: {
      title?: string
    },
  ) => http.post<NoteDetail>('/api/notes/update', payload, { id }),

  delete: (id: string) => http.post<{ ok: boolean }>('/api/notes/delete', undefined, { id }),

}
