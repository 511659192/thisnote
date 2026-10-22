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

  importUploads: async (files: File[]): Promise<FolderImportResult> => {
    const summary: FolderImportResult = { imported: 0, duplicates: 0, skipped: 0, errors: [] }
    for (const file of files) {
      const form = new FormData()
      form.append('file', file, file.name)
      try {
        const result = await http.upload<FolderImportResult>(`${BASE}/import-upload`, form)
        summary.imported += result.imported
        summary.duplicates += result.duplicates
        summary.skipped += result.skipped
        summary.errors.push(...result.errors)
      } catch (e) {
        summary.errors.push(`${file.name}：${(e as Error).message}`)
      }
    }
    return summary
  },
}
