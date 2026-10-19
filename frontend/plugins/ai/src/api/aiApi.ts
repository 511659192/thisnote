import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/ai'

export interface AiSettings {
  provider: string
  model: string
  maxTextChars: number
  keyConfigured: boolean
}

export interface AiSettingsUpdate {
  provider: string
  model: string
  apiKey?: string
  clearApiKey?: boolean
  maxTextChars: number
}

export const aiApi = {
  settings: () => http.get<AiSettings>(BASE + '/settings'),
  saveSettings: (body: AiSettingsUpdate) => http.post<AiSettings>(BASE + '/settings', body),
  test: () => http.post<{ success: boolean; message: string }>(BASE + '/test'),
}
