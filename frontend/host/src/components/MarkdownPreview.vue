<script setup lang="ts">
import { computed, ref } from 'vue'
import { renderMarkdown } from '@/core/markdown'
import { attachmentApi } from '@/api'

const props = defineProps<{
  content: string
  /** Resolve a wiki-link target to a node id, or undefined when unresolved. */
  resolveLink?: (target: string) => string | undefined
}>()

const emit = defineEmits<{
  (e: 'open', noteId: string): void
  (e: 'create', title: string): void
  (e: 'tag', tag: string): void
  (e: 'toggle-task', payload: { index: number; checked: boolean }): void
}>()

const root = ref<HTMLElement | null>(null)

/**
 * Attachments are stored under the local attachment root; rewrite relative image paths
 * so pasted/imported images resolve through the backend.
 */
const html = computed(() =>
  renderMarkdown(props.content ?? '').replace(
    /src="(?!https?:|data:|\/api\/)([^"]+)"/g,
    (_m, rel: string) => `src="${attachmentApi.url(rel.replace(/^\.\//, ''))}"`,
  ),
)

function onClick(event: MouseEvent) {
  const el = (event.target as HTMLElement).closest('[data-wikilink], [data-tag]') as HTMLElement | null
  if (!el) return
  const link = el.dataset.wikilink
  if (link) {
    const id = props.resolveLink?.(link)
    if (id) emit('open', id)
    else emit('create', link)
    return
  }
  const tag = el.dataset.tag
  if (tag) emit('tag', tag)
}
</script>

<template>
  <div ref="root" class="md-preview" v-html="html" @click="onClick"></div>
</template>

<style scoped>
.md-preview {
  padding: 0;
}
</style>
