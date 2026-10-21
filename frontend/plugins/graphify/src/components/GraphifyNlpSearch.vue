<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  graphifyApi,
  type GraphifyNlpSearchResponse,
} from '../api/graphifyApi'

const router = useRouter()
const route = useRoute()
const query = ref('')
const open = ref(false)
const loading = ref(false)
const error = ref('')
const result = ref<GraphifyNlpSearchResponse | null>(null)
const active = ref(0)

let requestVersion = 0

watch(query, () => {
  requestVersion++
  loading.value = false
  result.value = null
  error.value = ''
  active.value = 0
})

watch(() => route.fullPath, () => resetSearch())

async function searchNotes(): Promise<void> {
  const value = query.value.trim()
  if (value.length < 2) {
    error.value = '请输入至少 2 个字符的查询'
    open.value = true
    return
  }
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  result.value = null
  active.value = 0
  open.value = true
  try {
    const response = await graphifyApi.search(value)
    if (version !== requestVersion) return
    result.value = response
    if (!response.available) {
      error.value = '请先完成 Graphify 首次索引'
    }
  } catch (e) {
    if (version === requestVersion) {
      error.value = 'NLP 查询失败：' + (e as Error).message
    }
  } finally {
    if (version === requestVersion) {
      loading.value = false
    }
  }
}

function submit(): void {
  if (result.value?.available) {
    const note = result.value.notes[active.value] ?? result.value.notes[0]
    if (note) openNote(note.noteId)
    return
  }
  void searchNotes()
}

function openNote(noteId: string): void {
  if (!/^[0-9a-f-]{36}$/i.test(noteId)) return
  resetSearch()
  void router.push(`/notes/${encodeURIComponent(noteId)}`)
}

function resetSearch(): void {
  requestVersion++
  open.value = false
  loading.value = false
  query.value = ''
  result.value = null
  error.value = ''
  active.value = 0
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    resetSearch()
    ;(event.target as HTMLInputElement)?.blur()
    return
  }
  if (event.key === 'ArrowDown' && result.value?.notes.length) {
    event.preventDefault()
    active.value = Math.min(active.value + 1, result.value.notes.length - 1)
    return
  }
  if (event.key === 'ArrowUp' && result.value?.notes.length) {
    event.preventDefault()
    active.value = Math.max(active.value - 1, 0)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
}

function onDocClick(event: MouseEvent): void {
  const el = event.target as HTMLElement
  if (!el.closest('.topbar-nlp-search')) open.value = false
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => {
  requestVersion++
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div class="topbar-nlp-search">
    <div class="search-box" :class="{ filled: query.trim() }">
      <span class="ai-mark" aria-hidden="true">✦</span>
      <input
        v-model="query"
        type="text"
        maxlength="200"
        spellcheck="false"
        placeholder="自然语言搜索笔记…"
        @focus="open = true"
        @keydown="onKeydown"
      />
      <button
        v-if="query"
        type="button"
        class="clear"
        title="清空 (Esc)"
        @click="resetSearch"
      >×</button>
      <button
        type="button"
        class="submit"
        title="查询 (Enter)"
        :disabled="loading || query.trim().length < 2"
        @click="searchNotes"
      >→</button>
    </div>

    <div v-if="open" class="dropdown">
      <div v-if="loading" class="status muted">Graphify 正在召回图谱候选，AI 正在解读…</div>
      <div v-else-if="error" class="status error">{{ error }}</div>
      <template v-else-if="result?.available">
        <p class="answer">{{ result.answer }}</p>
        <button
          v-for="(note, index) in result.notes"
          :key="note.noteId"
          type="button"
          class="result"
          :class="{ active: index === active }"
          @click="openNote(note.noteId)"
          @mousemove="active = index"
        >
          <span class="title">{{ note.title }}</span>
          <span class="reason">{{ note.reason }}</span>
        </button>
        <div v-if="!result.notes.length" class="status muted">没有匹配到可打开的笔记。</div>
      </template>
      <div v-else class="hint">
        <strong>Graphify + AI</strong>
        <span>输入自然语言问题后按 Enter，例如“我在哪里处理过稳定性治理？”</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topbar-nlp-search {
  position: relative;
  width: 240px;
  margin-left: var(--space-3);
  transition: width 0.18s ease;
}

.topbar-nlp-search:focus-within {
  width: 360px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 5px;
  box-sizing: border-box;
  width: 100%;
  min-height: 28px;
  padding: 2px 4px 2px 9px;
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: 999px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.search-box:focus-within {
  background: var(--c-surface);
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 14%, transparent);
}

.ai-mark {
  flex: 0 0 auto;
  color: var(--accent);
  font-size: 12px;
  line-height: 1;
}

.search-box input {
  flex: 1;
  min-width: 0;
  height: 22px;
  padding: 0;
  color: var(--c-text);
  background: transparent;
  border: 0;
  outline: 0;
  font: inherit;
  font-size: 12.5px;
}

.search-box input::placeholder {
  color: var(--c-text-3);
}

.clear,
.submit {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  line-height: 1;
  cursor: pointer;
}

.clear {
  color: var(--c-text-3);
  background: transparent;
  font-size: 15px;
}

.clear:hover {
  color: var(--c-text);
  background: var(--c-surface-3);
}

.submit {
  color: #fff;
  background: var(--accent);
  font-size: 13px;
}

.submit:disabled {
  opacity: 0.35;
  cursor: default;
}

.dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  box-sizing: border-box;
  width: min(470px, calc(100vw - 24px));
  max-height: 420px;
  overflow-y: auto;
  padding: 6px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  z-index: 60;
}

.status,
.hint,
.answer {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
}

.status {
  padding: 9px 10px;
}

.error {
  color: var(--c-danger, #c33);
}

.hint {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  color: var(--c-text-muted, #8b9098);
}

.hint strong {
  color: var(--accent);
  font-size: 11px;
}

.answer {
  padding: 8px 10px;
  color: var(--c-text);
  background: var(--accent-soft);
  border-radius: 6px;
}

.result {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 7px 10px;
  color: var(--c-text);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
}

.result:hover,
.result.active {
  background: var(--accent-soft);
}

.title,
.reason {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  font-size: 13px;
  font-weight: 600;
}

.reason {
  color: var(--c-text-muted, #8b9098);
  font-size: 11.5px;
}

@media (max-width: 760px) {
  .topbar-nlp-search {
    width: 170px;
  }
  .topbar-nlp-search:focus-within {
    width: 260px;
  }
}
</style>
