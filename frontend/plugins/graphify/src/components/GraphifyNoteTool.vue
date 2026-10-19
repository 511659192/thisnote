<script setup lang="ts">
import GraphifyRelatedNotes from './GraphifyRelatedNotes.vue'

defineProps<{ noteId: string }>()
</script>

<template>
  <div class="graphify-note-tool">
    <GraphifyRelatedNotes :note-id="noteId" />
  </div>
</template>

<style scoped>
.graphify-note-tool {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  min-width: 0;
}
</style>
