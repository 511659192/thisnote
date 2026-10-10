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
  get: (id: string) => http.get<NoteDetail>('/api/notes/detail', { id }),

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

  update: (
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
  ) => http.post<NoteDetail>('/api/notes/update', payload, { id }),

  delete: (id: string) => http.post<{ ok: boolean }>('/api/notes/delete', undefined, { id }),

  addTag: (id: string, name: string) =>
    http.post<NoteDetail['tags']>('/api/notes/tags/add', undefined, { id, name }),
  removeTag: (id: string, name: string) =>
    http.post<NoteDetail['tags']>('/api/notes/tags/remove', undefined, { id, name }),
  resolveStub: (title: string, fromNodeId?: string) =>
    http.post<NoteDetail>('/api/notes/resolve-stub', { title, fromNodeId }),
}
