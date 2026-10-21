<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { emitNoteTitleChange, toast } from '@thisnote/plugin-api'
import { aiTitleApi, type AiTitleSuggestions } from '../api/aiTitleApi'

const props = defineProps<{ noteId: string }>()
const POLL_INTERVAL_MS = 1_500
const POLL_DEADLINE_MS = 140_000

const root = ref<HTMLElement | null>(null)
const open = ref(false)
const busy = ref(false)
const status = ref('')
const error = ref('')
const candidates = ref<string[]>([])
let requestVersion = 0

function close(): void {
  open.value = false
  requestVersion++
  busy.value = false
}

function closeOnOutsidePointerDown(event: PointerEvent): void {
  if (!open.value) return
  const target = event.target
  if (target instanceof Node && root.value?.contains(target)) return
  close()
}

function toggle(): void {
  if (open.value) {
    close()
    return
  }
  void suggest()
}

async function suggest(): Promise<void> {
  if (busy.value) return
  const version = ++requestVersion
  const noteId = props.noteId
  open.value = true
  busy.value = true
  status.value = '正在生成标题候选…'
  error.value = ''
  candidates.value = []
  try {
    const result = await aiTitleApi.suggest(noteId)
    if (version !== requestVersion || props.noteId !== noteId) return
    apply(result)
    if (result.pending) {
      await poll(noteId, result.deadlineEpochMs, version)
    }
  } catch (e) {
    if (version !== requestVersion) return
    error.value = (e as Error).message
    status.value = ''
  } finally {
    if (version === requestVersion) busy.value = false
  }
}

function apply(view: AiTitleSuggestions): void {
  candidates.value = view.candidates
  error.value = view.error
  if (view.pending) {
    status.value = '正在生成标题候选…'
  } else {
    status.value = view.error || view.candidates.length ? '' : '没有可用的标题候选'
  }
}

async function poll(noteId: string, deadlineEpochMs: number, version: number): Promise<void> {
  const deadline = Math.min(deadlineEpochMs, Date.now() + POLL_DEADLINE_MS)
  while (Date.now() <= deadline) {
    await new Promise((resolve) => window.setTimeout(resolve, POLL_INTERVAL_MS))
    if (version !== requestVersion || props.noteId !== noteId) return
    try {
      const view = await aiTitleApi.status(noteId)
      if (version !== requestVersion || props.noteId !== noteId) return
      apply(view)
      if (!view.pending) return
    } catch (e) {
      if (version !== requestVersion) return
      error.value = (e as Error).message
      status.value = ''
      return
    }
  }
  if (version === requestVersion && props.noteId === noteId) {
    status.value = ''
    error.value = 'AI 标题未在预期时间内完成，请稍后重试'
  }
}

async function choose(title: string): Promise<void> {
  if (busy.value) return
  const noteId = props.noteId
  const version = ++requestVersion
  busy.value = true
  status.value = '正在替换标题…'
  error.value = ''
  try {
    const updated = await aiTitleApi.applyTitle(noteId, title)
    if (version !== requestVersion || props.noteId !== noteId) return
    emitNoteTitleChange({ noteId: updated.noteId, title: updated.title })
    toast('笔记标题已替换', 'success')
    open.value = false
  } catch (e) {
    if (version !== requestVersion) return
    error.value = (e as Error).message
    status.value = ''
  } finally {
    if (version === requestVersion) busy.value = false
  }
}

watch(() => props.noteId, () => {
  requestVersion++
  open.value = false
  busy.value = false
  status.value = ''
  error.value = ''
  candidates.value = []
})

onMounted(() => {
  document.addEventListener('pointerdown', closeOnOutsidePointerDown, true)
})

onBeforeUnmount(() => {
  requestVersion++
  document.removeEventListener('pointerdown', closeOnOutsidePointerDown, true)
})
</script>

<template>
  <div ref="root" class="ai-title-tool">
    <button
      type="button"
      class="title-trigger"
      :disabled="busy"
      :aria-expanded="open"
      aria-haspopup="dialog"
      :title="open ? '收起 AI 标题候选' : '生成 AI 标题候选'"
      @click="toggle"
    >
      <span class="glyph">✦</span>
      <span>AI 标题</span>
    </button>

    <section v-if="open" class="title-popover" role="dialog" aria-label="AI 标题候选">
      <header class="popover-head">
        <strong>选择新标题</strong>
        <button type="button" class="close" title="收起候选" @click="close">
          收起 <span aria-hidden="true">⌃</span>
        </button>
      </header>

      <p v-if="status" class="status">{{ status }}</p>
      <p v-if="error" class="error">{{ error }}</p>

      <ul v-if="candidates.length" class="candidate-list">
        <li v-for="candidate in candidates" :key="candidate">
          <button type="button" :disabled="busy" @click="choose(candidate)">
            {{ candidate }}
          </button>
        </li>
      </ul>

      <button v-if="error" type="button" class="retry" :disabled="busy" @click="suggest">
        重新生成
      </button>
    </section>
  </div>
</template>

<style scoped>
.ai-title-tool {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
}

.title-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 7px;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  font: inherit;
  font-size: 11px;
  line-height: 1.4;
  cursor: pointer;
}

.title-trigger:hover:not(:disabled),
.title-trigger[aria-expanded='true'] {
  color: var(--accent, #6258d5);
  background: color-mix(in srgb, var(--accent, #6258d5) 8%, transparent);
  border-color: color-mix(in srgb, var(--accent, #6258d5) 22%, transparent);
}

.title-trigger:disabled {
  cursor: wait;
  opacity: 0.6;
}

.glyph {
  color: var(--accent, #6258d5);
  font-size: 12px;
}

.title-popover {
  position: absolute;
  z-index: 80;
  top: calc(100% + 7px);
  right: 0;
  box-sizing: border-box;
  width: min(360px, 70vw);
  padding: 11px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 9px;
  box-shadow: 0 10px 30px #0002;
}

.popover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
}

.close {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 4px;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 0;
  border-radius: 4px;
  font: inherit;
  font-size: 11px;
  line-height: 1.2;
  cursor: pointer;
}

.close:hover {
  color: var(--accent, #6258d5);
  background: color-mix(in srgb, var(--accent, #6258d5) 8%, transparent);
}

.candidate-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.candidate-list button {
  width: 100%;
  padding: 8px 9px;
  color: var(--c-text);
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: 7px;
  font: inherit;
  font-size: 12px;
  line-height: 1.45;
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
}

.candidate-list button:hover:not(:disabled) {
  color: var(--accent, #6258d5);
  border-color: var(--accent, #6258d5);
}

.candidate-list button:disabled {
  cursor: wait;
  opacity: 0.55;
}

.status,
.error {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
}

.status {
  color: var(--c-text-muted, #8b9098);
}

.error {
  color: var(--c-danger, #c33);
  margin-bottom: 8px;
}

.retry {
  margin-top: 8px;
  padding: 4px 8px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  font-size: 11px;
  cursor: pointer;
}
</style>
