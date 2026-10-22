<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { graphifyApi, type GraphifyRelatedNotes } from '../api/graphifyApi'

const props = defineProps<{ noteId: string; placement?: 'title' | 'below-title' | 'page-footer' }>()
const router = useRouter()
const RELATED_PREVIEW_COUNT = 3
const result = ref<GraphifyRelatedNotes>({ available: false, notes: [] })
const loading = ref(true)
const expanded = ref(false)
const error = ref('')
const visibleNotes = computed(() => expanded.value ? result.value.notes : result.value.notes.slice(0, RELATED_PREVIEW_COUNT))
const hasMore = computed(() => result.value.notes.length > RELATED_PREVIEW_COUNT)
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

function toggleExpanded(): void {
  expanded.value = !expanded.value
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
  (noteId) => {
    expanded.value = false
    void load(noteId)
  },
  { immediate: true },
)
</script>

<template>
  <section class="related-notes" :class="{ 'page-footer-tool': props.placement === 'page-footer', expanded }" aria-label="相关笔记">
    <button
      type="button"
      class="related-toggle"
      :disabled="!hasMore"
      :aria-expanded="hasMore ? expanded : undefined"
      :title="hasMore ? (expanded ? '收起相关笔记' : '展开全部相关笔记') : '相关笔记'"
      @click.stop="toggleExpanded"
    >
      <span v-if="hasMore" class="related-chevron" aria-hidden="true">{{ expanded ? '▾' : '▸' }}</span>
      <span>相关笔记</span>
      <span v-if="result.notes.length" class="related-count">{{ result.notes.length }}</span>
    </button>
    <span v-if="loading" class="related-hint">查找中…</span>
    <span v-else-if="error" class="related-hint error" :title="error">加载失败</span>
    <div v-else-if="result.available && result.notes.length" class="related-list" :class="{ expanded }">
      <button
        v-for="note in visibleNotes"
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
  box-sizing: border-box;
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 4px 8px;
  width: 100%;
  min-width: 0;
  padding: 5px 7px;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: 8px;
}

.related-notes.page-footer-tool {
  padding: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.related-notes.expanded {
  align-items: flex-start;
  flex-wrap: wrap;
}

.related-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  padding: 1px 7px;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 10px;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.related-toggle:hover,
.related-toggle[aria-expanded='true'] {
  color: var(--accent, #6258d5);
  background: color-mix(in srgb, var(--accent, #6258d5) 8%, transparent);
}

.related-toggle:disabled {
  cursor: default;
}

.related-toggle:disabled:hover {
  color: var(--c-text-muted, #8b9098);
  background: transparent;
}

.related-count {
  min-width: 16px;
  padding: 0 4px;
  color: var(--c-text-muted, #8b9098);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  font-size: 10px;
  line-height: 1.35;
  text-align: center;
}

.related-chevron {
  font-size: 10px;
  line-height: 1;
}

.related-list {
  display: flex;
  flex: 1 1 0;
  flex-wrap: nowrap;
  gap: 4px 6px;
  min-width: 0;
  overflow: hidden;
}

.related-list.expanded {
  flex-basis: 100%;
  flex-wrap: wrap;
  overflow: visible;
}

.related-list .related-item {
  flex: 0 0 auto;
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
  margin: 0;
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
  line-height: 1.5;
}

.related-hint.error {
  color: var(--c-danger, #c33);
}
</style>
