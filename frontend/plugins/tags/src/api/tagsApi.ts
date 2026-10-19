import { http } from '@thisnote/plugin-api'

export const TAGS_PLUGIN_ID = 'tags'
export const TAGS_FILTER_ID = 'tags'
const BASE = '/api/plugins/tags'

export type TagsByNote = Record<string, string[]>

export const tagsApi = {
  tags: () => http.get<TagsByNote>(BASE + '/tags'),
  usage: () => http.get<Record<string, number>>(BASE + '/tags/usage'),
  add: (noteId: string, tag: string) =>
    http.post<{ noteId: string; tags: string[] }>(BASE + '/tags/add', { noteId, tag }),
  remove: (noteId: string, tag: string) =>
    http.post<{ noteId: string; tags: string[] }>(BASE + '/tags/remove', { noteId, tag }),
  auto: (noteId: string) =>
    http.post<{ noteId: string; tags: string[] }>(BASE + '/tags/auto', { noteId }),
  removeEverywhere: (tag: string) =>
    http.post<{ removedNotes: number }>(BASE + '/tags/delete', { tag }),
}
