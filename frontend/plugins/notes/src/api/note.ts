import { http } from '@thisnode/plugin-api'
import type { NoteDto } from './types'

export interface NoteQuery {
  bookId?: string
  text?: string
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteDto[]>('/api/notes/get', query as never),
  get: (noteId: string) => http.get<NoteDto>('/api/notes/get-detail', { noteId }),

  create: (payload: {
    bookId?: string
  }) => http.post<NoteDto>('/api/notes/create', payload),

  update: (
    noteId: string,
    payload: {
      title?: string
    },
  ) => http.post<NoteDto>('/api/notes/update', payload, { noteId }),

  delete: (id: string) => http.post<{ ok: boolean }>('/api/notes/delete', undefined, { id }),

}
