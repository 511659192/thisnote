import { http } from '@thisnode/plugin-api'

export function getClipboardImage(data: DataTransfer | null): Blob | null {
  if (!data) return null
  const item = Array.from(data.items).find(
    (candidate) => candidate.kind === 'file' && candidate.type.startsWith('image/'),
  )
  const itemImage = item?.getAsFile()
  if (itemImage) return itemImage
  return Array.from(data.files).find((file) => file.type.startsWith('image/')) ?? null
}

export async function getImageDimensions(image: Blob): Promise<{ width: number; height: number }> {
  const bitmap = await createImageBitmap(image)
  try {
    return { width: bitmap.width, height: bitmap.height }
  } finally {
    bitmap.close()
  }
}

export async function uploadImageAttachment(image: Blob): Promise<string> {
  const form = new FormData()
  form.append('file', image, 'clipboard-image')
  const attachmentPath = await http.upload<string>('/api/notes/attachments/upload', form)
  return `![粘贴的图片](${attachmentPath})`
}

export async function deleteUnreferencedImageAttachment(markdown: string): Promise<void> {
  const match = /\.\.\/attachments\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:png|jpg))/.exec(markdown)
  if (match) await http.post<void>('/api/notes/attachments/delete', undefined, { fileName: match[1] })
}

async function asPng(image: Blob): Promise<Blob> {
  if (image.type === 'image/png') return image

  const bitmap = await createImageBitmap(image)
  try {
    const canvas = document.createElement('canvas')
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法处理图片')
    context.drawImage(bitmap, 0, 0)
    return await new Promise((resolve, reject) => {
      canvas.toBlob((blob) => {
        if (blob) resolve(blob)
        else reject(new Error('无法转换图片'))
      }, 'image/png')
    })
  } finally {
    bitmap.close()
  }
}

export async function copyImageToClipboard(src: string): Promise<void> {
  if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') {
    throw new Error('当前浏览器不支持复制图片')
  }

  const response = await fetch(src)
  if (!response.ok) throw new Error('无法读取图片')
  const image = await asPng(await response.blob())
  await navigator.clipboard.write([new ClipboardItem({ 'image/png': image })])
}
