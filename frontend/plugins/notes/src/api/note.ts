import { http } from '@thisnode/plugin-api'
import type { NoteMeta } from './types'

export interface NoteQuery {
  notebookId?: string
  type?: string
  text?: string
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteMeta[]>('/api/notes', query as never),
  get: (noteId: string) => http.get<NoteMeta>('/api/notes/detail', { noteId }),

  create: (payload: {
    type?: string
    notebookId?: string
  }) => http.post<NoteMeta>('/api/notes/create', payload),

  update: (
    noteId: string,
    payload: {
      title?: string
    },
  ) => http.post<NoteMeta>('/api/notes/update', payload, { noteId }),

  delete: (id: string) => http.post<{ ok: boolean }>('/api/notes/delete', undefined, { id }),

}
