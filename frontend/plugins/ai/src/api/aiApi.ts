import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/ai'

export interface AiProfile {
  id: string
  label: string
  provider: string
  model: string
  endpoint: string
  maxTextChars: number | null
  keyConfigured: boolean
}

export interface AiSettings {
  profiles: AiProfile[]
}

export interface AiProfileUpdate {
  id: string
  label: string
  provider: string
  model: string
  endpoint: string
  maxTextChars?: number | null
  apiKey?: string
  clearApiKey?: boolean
}

export const aiApi = {
  settings: () => http.get<AiSettings>(BASE + '/settings'),
  saveProfile: (body: AiProfileUpdate) => http.post<AiProfile>(BASE + '/profiles/save', body),
  deleteProfile: (profileId: string) => http.post<void>(BASE + '/profiles/delete', { profileId }),
  testProfile: (profileId: string) =>
    http.post<{ success: boolean; message: string }>(BASE + '/profiles/test', { profileId }),
}
