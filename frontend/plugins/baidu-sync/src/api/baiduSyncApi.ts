import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/baidu-sync'

export interface BaiduSyncSettingsView {
  appName: string
  hasAppKey: boolean
  hasSecretKey: boolean
  hasSignKey: boolean
  authorized: boolean
  autoSync: boolean
  notesRoot: string
  remoteDirectory: string
}

export interface BaiduSyncStatus {
  running: boolean
  uploaded: number
  downloaded: number
  deleted: number
  conflicts: number
  error: string
  items: BaiduSyncProgressItem[]
}

export interface BaiduSyncPreviewItem {
  path: string
  action: 'upload' | 'download' | 'delete-local' | 'delete-remote' | 'overwrite-local' | 'overwrite-remote'
  conflict: boolean
}

export interface BaiduSyncProgressItem extends BaiduSyncPreviewItem {
  state: 'pending' | 'running' | 'done' | 'failed'
  error: string | null
}

export interface BaiduSyncPreview {
  remoteDirectoryExists: boolean
  uploadCount: number
  downloadCount: number
  deleteCount: number
  overwriteCount: number
  conflictCount: number
  consistentCount: number
  items: BaiduSyncPreviewItem[]
}

export const baiduSyncApi = {
  settings: () => http.get<BaiduSyncSettingsView>(`${BASE}/get-settings`),
  saveSettings: (body: { appName: string; appKey: string; secretKey: string; signKey: string }) =>
    http.post<BaiduSyncSettingsView>(`${BASE}/update-settings`, body),
  authorizationUrl: async () => (await http.get<{ url: string }>(`${BASE}/get-authorization-url`)).url,
  exchangeCode: (code: string) => http.post<BaiduSyncSettingsView>(`${BASE}/exchange-code`, { code }),
  setAutoSync: (value: boolean) =>
    http.post<BaiduSyncSettingsView>(`${BASE}/update-auto-sync`, {}, { value }),
  sync: () => http.post<BaiduSyncStatus>(`${BASE}/sync`),
  status: () => http.get<BaiduSyncStatus>(`${BASE}/get-sync-status`),
  preview: () => http.get<BaiduSyncPreview>(`${BASE}/get-diff-preview`),
}
