<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { graphifyApi, type GraphifyRelatedNotes } from '../api/graphifyApi'

const props = defineProps<{ noteId: string }>()
const router = useRouter()
const result = ref<GraphifyRelatedNotes>({ available: false, notes: [] })
const loading = ref(true)
const error = ref('')
let requestSeq = 0

async function load(noteId: string): Promise<void> {
  const seq = ++requestSeq
  if (!noteId) {
    result.value = { available: false, notes: [] }
    loading.value = false
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const next = await graphifyApi.related(noteId)
    if (seq !== requestSeq) return
    result.value = next
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = (e as Error).message
    result.value = { available: false, notes: [] }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function openNote(noteId: string): void {
  const target: RouteLocationRaw = router.hasRoute('note')
    ? { name: 'note', params: { id: noteId } }
    : { path: '/notes/' + encodeURIComponent(noteId) }
  const resolved = router.resolve(target)
  window.open(new URL(resolved.href, window.location.href).href, '_blank', 'noopener,noreferrer')
}

watch(
  () => props.noteId,
  (noteId) => void load(noteId),
  { immediate: true },
)
</script>

<template>
  <section class="related-notes" aria-label="相关笔记">
    <span class="related-label">相关笔记</span>
    <span v-if="loading" class="related-hint">查找中…</span>
    <span v-else-if="error" class="related-hint error" :title="error">加载失败</span>
    <div v-else-if="result.available && result.notes.length" class="related-list">
      <button
        v-for="note in result.notes"
        :key="note.noteId"
        type="button"
        class="related-item"
        :title="note.title + '：' + note.reasons.join('；')"
        @click.stop="openNote(note.noteId)"
      >
        <span class="related-title">{{ note.title }}</span>
        <span class="related-reason">{{ note.reasons.join(' · ') }}</span>
      </button>
    </div>
    <span v-else class="related-hint">{{ result.available ? '暂无相关笔记' : '索引完成后可用' }}</span>
  </section>
</template>

<style scoped>
.related-notes {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 4px 8px;
  width: 100%;
  min-width: 0;
}

.related-label {
  flex: 0 0 auto;
  padding-top: 4px;
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
  line-height: 1.5;
}

.related-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  min-width: 0;
}

.related-item {
  display: grid;
  gap: 1px;
  max-width: min(340px, 100%);
  min-width: 0;
  padding: 3px 7px;
  color: var(--c-text);
  font: inherit;
  text-align: left;
  background: color-mix(in srgb, var(--accent, #6258d5) 5%, var(--c-surface, #fff));
  border: 1px solid color-mix(in srgb, var(--accent, #6258d5) 20%, var(--c-border));
  border-radius: 6px;
  cursor: pointer;
}

.related-item:hover {
  background: color-mix(in srgb, var(--accent, #6258d5) 10%, var(--c-surface, #fff));
  border-color: var(--accent, #6258d5);
}

.related-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.4;
}

.related-reason {
  display: -webkit-box;
  overflow: hidden;
  color: var(--c-text-muted, #8b9098);
  font-size: 10px;
  line-height: 1.35;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.related-hint {
  padding-top: 4px;
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
}

.related-hint.error {
  color: var(--c-danger, #c33);
}
</style>
