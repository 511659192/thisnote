import { http } from '@thisnode/plugin-api'
import type { NoteMeta } from './types'

export interface NoteQuery {
  notebookId?: string
  text?: string
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteMeta[]>('/api/notes/get', query as never),
  get: (noteId: string) => http.get<NoteMeta>('/api/notes/get-detail', { noteId }),

  create: (payload: {
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
