import { http } from '@/core/http'

export const attachmentApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<{ name: string; url: string; size: number }>('/api/attachments/upload', form)
  },
  url: (relative: string) => http.url(`/api/attachments/${relative.replace(/^\/+/, '')}`),
}
