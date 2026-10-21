<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { aiSummaryApi, type AiSummaryView } from '../api/aiSummaryApi'

const props = defineProps<{ noteId: string }>()
const POLL_INTERVAL_MS = 2_000
const POLL_DEADLINE_MS = 140_000

const view = ref<AiSummaryView>(emptyView(''))
const loading = ref(false)
const working = ref(false)
const localError = ref('')
const collapsed = ref(false)
let requestVersion = 0

const busy = computed(() => loading.value || working.value || view.value.pending)
const displayedError = computed(() => localError.value || view.value.error)

function toggleCollapsed(): void {
  collapsed.value = !collapsed.value
}

function emptyView(noteId: string): AiSummaryView {
  return {
    noteId,
    summary: '',
    generatedAt: '',
    stale: false,
    pending: false,
    autoEnabled: true,
    error: '',
  }
}

function apply(noteId: string, next: AiSummaryView): void {
  if (props.noteId !== noteId) return
  view.value = next
}

async function load(noteId: string): Promise<void> {
  const version = ++requestVersion
  localError.value = ''
  working.value = false
  view.value = emptyView(noteId)
  loading.value = true
  try {
    const current = await aiSummaryApi.get(noteId)
    if (version !== requestVersion) return
    apply(noteId, current)
    loading.value = false
    if (current.pending) {
      working.value = true
      try {
        await poll(noteId, version)
      } finally {
        if (version === requestVersion) working.value = false
      }
    } else if (current.autoEnabled && (!current.summary || current.stale) && !current.error) {
      void generate(noteId, false, true, version)
    }
  } catch (e) {
    if (version !== requestVersion) return
    localError.value = (e as Error).message
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function generate(
  noteId: string,
  force: boolean,
  automatic: boolean,
  version = requestVersion,
): Promise<void> {
  if (working.value || version !== requestVersion) return
  working.value = true
  localError.value = ''
  try {
    const next = await aiSummaryApi.generate(noteId, force, automatic)
    if (version !== requestVersion) return
    apply(noteId, next)
    if (next.pending) {
      await poll(noteId, version)
    }
  } catch (e) {
    if (version !== requestVersion) return
    localError.value = (e as Error).message
  } finally {
    if (version === requestVersion) working.value = false
  }
}

async function poll(noteId: string, version: number): Promise<void> {
  const deadline = Date.now() + POLL_DEADLINE_MS
  while (Date.now() < deadline) {
    await new Promise((resolve) => window.setTimeout(resolve, POLL_INTERVAL_MS))
    if (version !== requestVersion || props.noteId !== noteId) return
    try {
      const next = await aiSummaryApi.get(noteId)
      if (version !== requestVersion || props.noteId !== noteId) return
      apply(noteId, next)
      if (!next.pending) return
    } catch (e) {
      if (version !== requestVersion) return
      view.value = { ...view.value, pending: false }
      localError.value = (e as Error).message
      return
    }
  }
  if (version === requestVersion) {
    view.value = { ...view.value, pending: false }
    localError.value = 'AI 总结未在预期时间内完成，请稍后重试'
  }
}

function refresh(): void {
  if (busy.value) return
  void generate(props.noteId, true, false)
}

watch(
  () => props.noteId,
  (noteId) => void load(noteId),
  { immediate: true },
)

onBeforeUnmount(() => {
  requestVersion++
})
</script>

<template>
  <section class="ai-summary" aria-live="polite">
    <div class="summary-head">
      <button
        type="button"
        class="collapse"
        :aria-expanded="!collapsed"
        :title="collapsed ? '展开 AI 总结' : '收起 AI 总结'"
        @click.stop="toggleCollapsed"
      >
        <span class="chevron" aria-hidden="true">{{ collapsed ? '▸' : '▾' }}</span>
        <span class="summary-label">AI 总结</span>
      </button>
      <span v-if="view.stale && view.summary" class="summary-state">
        {{ working || view.pending ? '更新中…' : '待更新' }}
      </span>
      <button
        type="button"
        class="refresh"
        :disabled="busy"
        title="重新生成 AI 总结"
        aria-label="重新生成 AI 总结"
        @click.stop="refresh"
      >↻</button>
    </div>
    <template v-if="!collapsed">
      <p v-if="view.summary" class="summary-text" :class="{ stale: view.stale }">{{ view.summary }}</p>
      <p v-else-if="view.pending || working" class="summary-hint">根据知识图谱生成中…</p>
      <p v-else-if="!displayedError && !view.autoEnabled" class="summary-hint">
        自动生成已关闭，点击 ↻ 生成 AI 总结
      </p>
      <p v-else-if="!displayedError" class="summary-hint">打开笔记后根据知识图谱自动生成</p>
      <p v-if="displayedError" class="summary-error" :title="displayedError">{{ displayedError }}</p>
    </template>
  </section>
</template>

<style scoped>
.ai-summary {
  display: grid;
  gap: 3px;
  width: 100%;
  min-width: 0;
  padding: 2px 0;
}

.summary-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 20px;
}

.collapse {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.collapse:hover {
  color: var(--accent, #6258d5);
}

.chevron {
  width: 10px;
  font-size: 10px;
  line-height: 1;
}

.summary-label {
  color: inherit;
  font-size: 11px;
  font-weight: 600;
}

.summary-state {
  padding: 1px 5px;
  color: var(--c-text-muted, #8b9098);
  background: var(--c-surface-2);
  border-radius: 8px;
  font-size: 10px;
}

.refresh {
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 0;
  border-radius: 50%;
  font: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.refresh:hover:not(:disabled) {
  color: var(--accent, #6258d5);
  background: color-mix(in srgb, var(--accent, #6258d5) 9%, transparent);
}

.refresh:disabled {
  cursor: wait;
  opacity: 0.5;
}

.summary-text,
.summary-hint,
.summary-error {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.summary-text {
  max-width: 960px;
  color: var(--c-text-2);
}

.summary-text.stale {
  color: var(--c-text-muted, #8b9098);
}

.summary-hint {
  color: var(--c-text-muted, #8b9098);
}

.summary-error {
  color: var(--c-danger, #c33);
}
</style>
