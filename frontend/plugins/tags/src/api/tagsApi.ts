import { http } from '@thisnote/plugin-api'

export const TAGS_PLUGIN_ID = 'tags'
export const TAGS_FILTER_ID = 'tags'
const BASE = '/api/plugins/tags'

export type TagsByNote = Record<string, string[]>

export interface TagsAiProfile {
  id: string
  label: string
  provider: string
  model: string
  keyConfigured: boolean
}

export interface TagsSettings {
  aiProfileId: string
  profiles: TagsAiProfile[]
  profilesError: string
}

export const tagsApi = {
  settings: () => http.get<TagsSettings>(BASE + '/settings'),
  saveSettings: (aiProfileId: string) =>
    http.post<TagsSettings>(BASE + '/settings', { aiProfileId }),
  tags: () => http.get<TagsByNote>(BASE + '/tags'),
  usage: () => http.get<Record<string, number>>(BASE + '/tags/usage'),
  add: (noteId: string, tag: string) =>
    http.post<{ noteId: string; tags: string[] }>(BASE + '/tags/add', { noteId, tag }),
  remove: (noteId: string, tag: string) =>
    http.post<{ noteId: string; tags: string[] }>(BASE + '/tags/remove', { noteId, tag }),
  auto: (noteId: string) =>
    http.post<{ noteId: string; tags: string[]; pending: boolean; deadlineEpochMs: number }>(
      BASE + '/tags/auto',
      { noteId },
    ),
  autoStatus: (noteId: string) =>
    http.get<{ noteId: string; tags: string[]; pending: boolean; deadlineEpochMs: number }>(
      BASE + '/tags/auto/status',
      { noteId },
    ),
  removeEverywhere: (tag: string) =>
    http.post<{ removedNotes: number }>(BASE + '/tags/delete', { tag }),
}
