import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/ai-summary'

export interface AiSummaryView {
  noteId: string
  summary: string
  generatedAt: string
  stale: boolean
  pending: boolean
  autoEnabled: boolean
  error: string
}

export interface AiSummaryProfile {
  id: string
  label: string
  provider: string
  model: string
  keyConfigured: boolean
}

export interface AiSummarySettings {
  aiProfileId: string
  autoEnabled: boolean
  fontSize: number
  profiles: AiSummaryProfile[]
  profilesError: string
}

export const aiSummaryApi = {
  get: (noteId: string) => http.get<AiSummaryView>(BASE + '/get', { noteId }),
  generate: (noteId: string, force = false, automatic = false) =>
    http.post<AiSummaryView>(BASE + '/generate', { noteId, force, automatic }),
  settings: () => http.get<AiSummarySettings>(BASE + '/settings'),
  saveSettings: (aiProfileId: string, autoEnabled: boolean, fontSize: number) =>
    http.post<AiSummarySettings>(BASE + '/settings', { aiProfileId, autoEnabled, fontSize }),
}
