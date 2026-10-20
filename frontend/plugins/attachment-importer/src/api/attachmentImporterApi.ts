import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/attachment-importer'

export interface FolderImportResult {
  imported: number
  duplicates: number
  skipped: number
  errors: string[]
}

export const attachmentImporterApi = {
  importFolder: (path: string) =>
    http.post<FolderImportResult>(`${BASE}/import-folder`, { path }),
}
