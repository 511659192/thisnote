<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { request } from '@thisnode/plugin-api'
import { renderMarkdown } from '@/core/markdown'

const props = defineProps<{
  content: string
}>()

const html = computed(() => renderMarkdown(props.content ?? ''))
const root = ref<HTMLElement | null>(null)
let renderGeneration = 0
let objectUrls: string[] = []

watch(html, async () => {
  const generation = ++renderGeneration
  await nextTick()
  for (const url of objectUrls) URL.revokeObjectURL(url)
  objectUrls = []

  const images = root.value?.querySelectorAll<HTMLImageElement>('[data-thisnode-attachment]') ?? []
  await Promise.all(Array.from(images, async (image) => {
    const fileName = image.dataset.thisnodeAttachment
    if (!fileName) return
    try {
      const response = await request<Response>('GET', '/api/notes/attachments/get', {
        query: { fileName },
        raw: true,
      })
      if (!response.ok) throw new Error('图片读取失败')
      const objectUrl = URL.createObjectURL(await response.blob())
      if (generation !== renderGeneration || !image.isConnected) {
        URL.revokeObjectURL(objectUrl)
        return
      }
      objectUrls.push(objectUrl)
      image.src = objectUrl
    } catch {
      if (generation === renderGeneration) image.alt = `${image.alt || '图片'}（加载失败）`
    }
  }))
}, { flush: 'post', immediate: true })

onBeforeUnmount(() => {
  renderGeneration++
  for (const url of objectUrls) URL.revokeObjectURL(url)
  objectUrls = []
})
</script>

<template>
  <div ref="root" class="md-preview" v-html="html"></div>
</template>

<style scoped>
.md-preview {
  padding: 0;
}

.md-preview :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 320px;
  object-fit: contain;
}
</style>
