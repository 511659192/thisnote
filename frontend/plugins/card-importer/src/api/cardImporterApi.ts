import { http } from '@thisnote/plugin-api'

const BASE = '/api/plugins/card-importer'

export interface FolderImportResult {
  imported: number
  duplicates: number
  skipped: number
  errors: string[]
}

export const cardImporterApi = {
  importFolder: (path: string) =>
    http.post<FolderImportResult>(`${BASE}/import-folder`, { path }),
}
