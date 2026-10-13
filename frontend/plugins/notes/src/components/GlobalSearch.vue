<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '@thisnode/plugin-api'

/**
 * 顶栏模糊查询（logo 右侧）：跨标题与摘要的模糊匹配，下拉结果可直接跳转。
 *
 * <p>匹配规则：查询词按空白拆成多个词，笔记的 title+summary 里**每个词都包含**才算命中
 * （大小写不敏感）；标题命中排在摘要命中前面。纯界面侧：拉一次全量列表（limit 2000，
 * 本地工具足够），短 TTL 缓存，不新增端点。搜索随 notes 核心微应用装载，不再是独立插件。
 *
 * <p>键盘：↑↓ 选择、Enter 打开、Esc 关闭；点击结果或点击外部同样关闭。
 * 打开结果即跳 `/notes/:id`（笔记域路由，由 notes 微应用登记）。
 */

interface NoteMetaLite {
  noteId: string
  title: string
  summary?: string
}

const router = useRouter()
const route = useRoute()

const query = ref('')
const open = ref(false)
const active = ref(0)
const loading = ref(false)

const CACHE_TTL = 10_000
let notesCache: { at: number; data: NoteMetaLite[] } | null = null

async function allNotes(): Promise<NoteMetaLite[]> {
  if (notesCache && Date.now() - notesCache.at < CACHE_TTL) return notesCache.data
  loading.value = true
  try {
    const data = await http.get<NoteMetaLite[]>('/api/notes/get', { limit: 2000 })
    notesCache = { at: Date.now(), data }
    return data
  } finally {
    loading.value = false
  }
}

/** 命中：每个查询词都出现在 title+summary 里；返回 0..2 的排序权重（标题命中优先）。 */
function matchScore(note: NoteMetaLite, tokens: string[]): number {
  const title = (note.title ?? '').toLowerCase()
  const summary = (note.summary ?? '').toLowerCase()
  let score = 0
  for (const t of tokens) {
    const inTitle = title.includes(t)
    const inSummary = summary.includes(t)
    if (!inTitle && !inSummary) return -1
    if (inTitle) score += 2
    if (inSummary) score += 1
  }
  return score
}

const results = ref<Array<{ note: NoteMetaLite; score: number }>>([])

function runQuery(q: string): void {
  const tokens = q.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length === 0) {
    results.value = []
    return
  }
  void allNotes().then((notes) => {
    // 查询可能在路上已变：丢弃过期结果
    if (query.value.trim().toLowerCase() !== tokens.join(' ')) return
    results.value = notes
      .map((note) => ({ note, score: matchScore(note, tokens) }))
      .filter((r) => r.score >= 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 12)
    active.value = 0
  })
}

// 防抖 200ms
let timer: number | null = null
watch(query, (q) => {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    open.value = q.trim().length > 0
    runQuery(q)
  }, 200)
})

function openNote(id: string): void {
  close()
  void router.push(`/notes/${id}`)
}

function close(): void {
  open.value = false
  query.value = ''
  results.value = []
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    close()
    ;(e.target as HTMLInputElement)?.blur()
    return
  }
  if (!open.value) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(active.value + 1, results.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (e.key === 'Enter') {
    const r = results.value[active.value]
    if (r) openNote(r.note.noteId)
  }
}

/** 点击外部关闭。 */
function onDocClick(e: MouseEvent): void {
  const el = e.target as HTMLElement
  if (!el.closest('.topbar-search')) close()
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

// 路由切换（打开结果、手动跳转）即收起
watch(() => route.fullPath, () => close())

/** 标题按首个查询词切段（安全 span 渲染，不做 v-html）。 */
function titleSegments(title: string): Array<{ text: string; hit: boolean }> {
  const t = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean)[0]
  if (!t) return [{ text: title, hit: false }]
  const at = title.toLowerCase().indexOf(t)
  if (at < 0) return [{ text: title, hit: false }]
  return [
    { text: title.slice(0, at), hit: false },
    { text: title.slice(at, at + t.length), hit: true },
    { text: title.slice(at + t.length), hit: false },
  ].filter((seg) => seg.text.length > 0)
}

/** 摘要片段：围绕首个命中词取前后 ~40 字。 */
function summarySnippet(note: NoteMetaLite): string {
  const summary = note.summary ?? ''
  const t = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean)[0]
  if (!t) return summary.slice(0, 80)
  const at = summary.toLowerCase().indexOf(t)
  if (at < 0) return summary.slice(0, 80)
  const from = Math.max(0, at - 30)
  return (from > 0 ? '…' : '') + summary.slice(from, at + t.length + 50) + '…'
}
</script>

<template>
  <div class="topbar-search">
    <input
      v-model="query"
      class="input"
      type="text"
      placeholder="模糊搜索笔记…"
      spellcheck="false"
      @keydown="onKeydown"
      @focus="query.trim() && (open = true)"
    />
    <div v-if="open" class="dropdown">
      <div v-if="loading && !results.length" class="empty small muted">搜索中…</div>
      <div v-else-if="!results.length" class="empty small muted">没有匹配的笔记</div>
      <button
        v-for="(r, i) in results"
        :key="r.note.noteId"
        class="result"
        :class="{ active: i === active }"
        @click="openNote(r.note.noteId)"
        @mousemove="active = i"
      >
        <span class="title">
          <span
            v-for="(seg, j) in titleSegments(r.note.title)"
            :key="j"
            :class="seg.hit ? 'hit' : ''"
          >{{ seg.text }}</span>
        </span>
        <span v-if="r.note.summary" class="snippet small muted">{{ summarySnippet(r.note) }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.topbar-search {
  position: relative;
  margin-left: var(--space-3);
  width: 220px;
  transition: width 0.18s ease;
}
.topbar-search:focus-within {
  width: 340px;
}
.input {
  width: 100%;
  padding: 5px 10px;
  border: 1px solid var(--c-border);
  border-radius: 999px;
  background: var(--c-bg);
  color: var(--c-text);
  font-size: 12.5px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.input::placeholder {
  color: var(--c-text-3);
}
.input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 14%, transparent);
}

.dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  width: 440px;
  max-height: 380px;
  overflow-y: auto;
  padding: var(--space-1) 0;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  z-index: 60;
}
.empty {
  padding: 10px var(--space-3);
}
.result {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px var(--space-3);
  text-align: left;
  color: var(--c-text);
}
.result.active,
.result:hover {
  background: var(--accent-soft);
}
.result .title {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result .hit {
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 3px;
  padding: 0 1px;
}
.result .snippet {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
