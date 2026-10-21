import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/ai-title'

export interface AiTitleProfile {
  id: string
  label: string
  provider: string
  model: string
  keyConfigured: boolean
}

export interface AiTitleSettings {
  aiProfileId: string
  profiles: AiTitleProfile[]
  profilesError: string
}

export interface AiTitleSuggestions {
  noteId: string
  currentTitle: string
  candidates: string[]
}

export interface AiTitleUpdate {
  noteId: string
  title: string
}

export const aiTitleApi = {
  settings: () => http.get<AiTitleSettings>(BASE + '/settings'),
  saveSettings: (aiProfileId: string) =>
    http.post<AiTitleSettings>(BASE + '/settings', { aiProfileId }),
  suggest: (noteId: string) => http.post<AiTitleSuggestions>(BASE + '/suggest', { noteId }),
  applyTitle: (noteId: string, title: string) =>
    http.post<AiTitleUpdate>(BASE + '/apply', { noteId, title }),
}
