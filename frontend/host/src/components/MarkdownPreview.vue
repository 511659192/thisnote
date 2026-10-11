<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/core/markdown'

const props = defineProps<{
  content: string
}>()

const html = computed(() => renderMarkdown(props.content ?? ''))
</script>

<template>
  <div class="md-preview" v-html="html"></div>
</template>

<style scoped>
.md-preview {
  padding: 0;
}
</style>
