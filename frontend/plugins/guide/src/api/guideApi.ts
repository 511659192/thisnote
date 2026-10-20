import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/guide'

export interface GuideEnvironment {
  done: boolean
  detail: string
}

export const guideApi = {
  environment: () => http.get<GuideEnvironment>(`${BASE}/environment`),
}
