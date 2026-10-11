<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/core/markdown'
import { attachmentApi } from '@/api'

const props = defineProps<{
  content: string
}>()

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
</script>

<template>
  <div class="md-preview" v-html="html"></div>
</template>

<style scoped>
.md-preview {
  padding: 0;
}
</style>
