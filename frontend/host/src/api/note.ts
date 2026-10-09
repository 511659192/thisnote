import { http } from '@/core/http'
import type { NoteDetail, NoteMeta, ListFilter } from './types'

export interface NoteQuery {
  filter?: ListFilter
  tag?: string
  notebookId?: string
  type?: string
  text?: string
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteMeta[]>('/api/notes', query as never),
  get: (id: string) => http.get<NoteDetail>(`/api/notes/${id}`),

  create: (payload: {
    title?: string
    contentMd?: string
    type?: string
    dailyDate?: string
    tags?: string[]
    source?: string
    status?: string
      notebookId?: string
  }) => http.post<NoteDetail>('/api/notes', payload),

  patch: (
    id: string,
    payload: {
      title?: string
      contentMd?: string
      source?: string
      status?: string
      tags?: string[]
      notebookId?: string
      clearNotebook?: boolean
    },
  ) => http.post<NoteDetail>(`/api/notes/${id}/patch`, payload),

  purge: (id: string) => http.post<{ ok: boolean }>(`/api/notes/${id}/delete`),

  addTag: (id: string, name: string) =>
    http.post<NoteDetail['tags']>(`/api/notes/${id}/tags/add`, { name }),
  removeTag: (id: string, name: string) =>
    http.post<NoteDetail['tags']>(`/api/notes/${id}/tags/remove`, undefined, { name }),
  resolveStub: (title: string, fromNodeId?: string) =>
    http.post<NoteDetail>('/api/notes/resolve-stub', { title, fromNodeId }),
}
