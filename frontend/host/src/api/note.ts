import { http } from '@/core/http'
import type { NoteDetail, NoteMeta, ListFilter } from './types'

export interface NoteQuery {
  filter?: ListFilter
  tag?: string
  notebookId?: string
  type?: string
  text?: string
  includeArchived?: boolean
  limit?: number
  offset?: number
}

export const noteApi = {
  list: (query: NoteQuery = {}) => http.get<NoteMeta[]>('/api/notes', query as never),
  counts: () => http.get<Record<string, number>>('/api/notes/counts'),
  get: (id: string) => http.get<NoteDetail>(`/api/notes/${id}`),

  create: (payload: {
    title?: string
    contentMd?: string
    type?: string
    dailyDate?: string
    tags?: string[]
    aliases?: string[]
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
          archived?: boolean
      tags?: string[]
      aliases?: string[]
      notebookId?: string
      clearNotebook?: boolean
    },
  ) => http.patch<NoteDetail>(`/api/notes/${id}`, payload),

  duplicate: (id: string) => http.post<NoteDetail>(`/api/notes/${id}/duplicate`),
  purge: (id: string) => http.del<{ ok: boolean }>(`/api/notes/${id}`, { hard: true }),
  restore: (id: string) => http.post<{ ok: boolean }>(`/api/notes/${id}/restore`),
  emptyTrash: () => http.del<{ deleted: number }>('/api/notes/trash'),  archive: (id: string, value: boolean) =>
    http.post<{ archived: boolean }>(`/api/notes/${id}/archive`, undefined, { value }),

  addTag: (id: string, name: string) =>
    http.post<NoteDetail['tags']>(`/api/notes/${id}/tags`, { name }),
  removeTag: (id: string, name: string) =>
    http.del<NoteDetail['tags']>(`/api/notes/${id}/tags/${encodeURIComponent(name)}`),
  setTags: (id: string, names: string[]) =>
    http.put<NoteDetail['tags']>(`/api/notes/${id}/tags`, names),

  daily: (date?: string, templateId?: string) =>
    http.post<NoteDetail>('/api/notes/daily', undefined, { date, templateId }),
  resolveStub: (title: string, fromNodeId?: string) =>
    http.post<NoteDetail>('/api/notes/resolve-stub', { title, fromNodeId }),
}
