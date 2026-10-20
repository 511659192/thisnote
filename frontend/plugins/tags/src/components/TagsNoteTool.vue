<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { emitPluginStateChange } from '@thisnote/plugin-api'
import { tagsApi, TAGS_PLUGIN_ID } from '../api/tagsApi'
import { refreshActiveTagFilter } from '../tagFilter'
import { buildTagUsage, loadTagState, noteTags, updateNoteTags } from '../tagStore'

const props = defineProps<{ noteId: string }>()
const AUTO_TAG_MIN = 10
const root = ref<HTMLElement | null>(null)
const open = ref(false)
const busy = ref(false)
const draft = ref('')
const error = ref('')
const loadError = ref('')
const tags = computed(() => noteTags[props.noteId] ?? [])
const hasTags = computed(() => tags.value.length > 0)
const tagSummary = computed(() => tags.value.join('；'))
const autoAttempted = new Set<string>()
let autoPollVersion = 0
const availableTags = computed(() => {
  const query = draft.value.trim().toLocaleLowerCase('zh-CN')
  const selected = new Set(tags.value)
  return buildTagUsage(noteTags)
    .filter((tag) => !selected.has(tag.label))
    .filter((tag) => !query || tag.label.toLocaleLowerCase('zh-CN').includes(query))
})

async function ensureTagState(): Promise<void> {
  try {
    await loadTagState(true)
    loadError.value = ''
    if (!autoAttempted.has(props.noteId) && tags.value.length < AUTO_TAG_MIN) {
      autoAttempted.add(props.noteId)
      busy.value = true
      try {
        await applyAutoTags()
      } finally {
        busy.value = false
      }
    }
  } catch (e) {
    loadError.value = (e as Error).message
  }
}

function closeOnOutsidePointerDown(event: PointerEvent): void {
  if (!open.value) return
  const target = event.target
  if (target instanceof Node && root.value?.contains(target)) return
  open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', closeOnOutsidePointerDown, true)
  void ensureTagState()
})
onBeforeUnmount(() => {
  autoPollVersion++
  document.removeEventListener('pointerdown', closeOnOutsidePointerDown, true)
})
watch(() => props.noteId, () => {
  open.value = false
  draft.value = ''
  error.value = ''
  void ensureTagState()
})

async function toggleEditor(): Promise<void> {
  open.value = !open.value
  if (!open.value) return
  error.value = ''
  busy.value = true
  try {
    await loadTagState(true)
    loadError.value = ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function addTagValue(tag: string): Promise<void> {
  if (!tag || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await tagsApi.add(props.noteId, tag)
    updateNoteTags(props.noteId, result.tags)
    draft.value = ''
    refreshActiveTagFilter()
    emitPluginStateChange({ pluginId: TAGS_PLUGIN_ID, noteIds: [props.noteId] })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function addTag(): Promise<void> {
  await addTagValue(draft.value.trim())
}

async function applyAutoTags(): Promise<void> {
  const result = await tagsApi.auto(props.noteId)
  updateNoteTags(props.noteId, result.tags)
  draft.value = ''
  refreshActiveTagFilter()
  emitPluginStateChange({ pluginId: TAGS_PLUGIN_ID, noteIds: [props.noteId] })
  if (result.pending) {
    void pollAutoTags(props.noteId, result.deadlineEpochMs)
  }
}

async function pollAutoTags(noteId: string, deadlineEpochMs: number): Promise<void> {
  const version = ++autoPollVersion
  while (Date.now() <= deadlineEpochMs) {
    await new Promise((resolve) => window.setTimeout(resolve, 2_000))
    if (version !== autoPollVersion || props.noteId !== noteId) return
    try {
      const status = await tagsApi.autoStatus(noteId)
      if (version !== autoPollVersion || props.noteId !== noteId) return
      updateNoteTags(noteId, status.tags)
      refreshActiveTagFilter()
      emitPluginStateChange({ pluginId: TAGS_PLUGIN_ID, noteIds: [noteId] })
      if (!status.pending) return
    } catch {
      return
    }
  }
  if (version === autoPollVersion && props.noteId === noteId) {
    error.value = 'AI 标签未在预期时间内完成，已保留本地标签'
  }
}

async function autoExtractTags(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await applyAutoTags()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function removeTag(tag: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await tagsApi.remove(props.noteId, tag)
    updateNoteTags(props.noteId, result.tags)
    refreshActiveTagFilter()
    emitPluginStateChange({ pluginId: TAGS_PLUGIN_ID, noteIds: [props.noteId] })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div ref="root" class="tags-note-tool" @click.stop>
    <button
      type="button"
      class="tag-toggle"
      :aria-expanded="open"
      :title="open ? '关闭标签' : '编辑标签'"
      @click.stop="toggleEditor"
    >
      <span class="tag-glyph" aria-hidden="true">🏷</span>
      <span>标签</span>
    </button>

    <span
      v-if="hasTags"
      class="tag-strip"
      role="group"
      :title="tagSummary"
      :aria-label="'笔记标签：' + tagSummary"
    >
      <span
        v-for="tag in tags"
        :key="tag"
        class="tag"
        :title="'标签：' + tag"
      >{{ tag }}</span>
    </span>
    <span v-else-if="loadError" class="load-error" :title="loadError">标签加载失败</span>
    <button
      v-if="tags.length < AUTO_TAG_MIN"
      type="button"
      class="auto-inline"
      :disabled="busy"
      title="自动抽取标签"
      aria-label="自动抽取标签"
      @click.stop="autoExtractTags"
    >✦</button>

    <div v-if="open" class="tag-popover" @click.stop>
      <div class="popover-heading">
        <strong>标签</strong>
        <button type="button" class="close" aria-label="关闭" @click.stop="open = false">×</button>
      </div>

      <div class="tag-group">
        <span class="group-label">笔记标签</span>
        <div v-if="tags.length" class="tag-list">
          <span v-for="tag in tags" :key="tag" class="tag">
            {{ tag }}
            <button type="button" :aria-label="'删除标签 ' + tag" :disabled="busy" @click.stop="removeTag(tag)">×</button>
          </span>
        </div>
        <span v-else class="empty">还没有标签</span>
      </div>

      <form class="add-form" @submit.prevent.stop="addTag">
        <input
          v-model="draft"
          maxlength="64"
          placeholder="搜索或添加标签"
          :disabled="busy"
          @keydown.stop
        />
        <button type="submit" :disabled="busy || !draft.trim()">添加</button>
        <button
          type="button"
          class="auto"
          :disabled="busy"
          title="结合本地文本与可用的视觉索引抽取最多 20 个高层标签"
          @click.stop="autoExtractTags"
        >自动抽取</button>
      </form>
      <div v-if="availableTags.length" class="tag-group suggestion-group">
        <span class="group-label">已有标签</span>
        <div class="tag-list suggestion-list">
          <button
            v-for="tag in availableTags"
            :key="tag.label"
            type="button"
            class="tag suggestion"
            :disabled="busy"
            :title="'添加标签：' + tag.label"
            @click.stop="addTagValue(tag.label)"
          >{{ tag.label }}</button>
        </div>
      </div>
      <p v-if="busy" class="muted">处理中…</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>

<style scoped>
.tags-note-tool {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
  width: 100%;
  min-width: 0;
}

.tag-strip {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
  min-width: 0;
}

.tag-toggle {
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
  font-size: 11px;
  line-height: 1.5;
  cursor: pointer;
}

.tag-toggle:hover,
.tag-toggle[aria-expanded='true'] {
  color: var(--accent, #6258d5);
  background: color-mix(in srgb, var(--accent, #6258d5) 8%, transparent);
}

.tag-glyph {
  font-size: 12px;
  line-height: 1;
}

.auto-inline {
  flex: 0 0 auto;
  width: 22px;
  height: 22px;
  padding: 0;
  color: var(--accent, #6258d5);
  background: transparent;
  border: 0;
  border-radius: 50%;
  font-size: 13px;
  cursor: pointer;
}

.auto-inline:hover {
  background: color-mix(in srgb, var(--accent, #6258d5) 10%, transparent);
}

.auto-inline:disabled {
  opacity: 0.5;
  cursor: default;
}

.tag-popover {
  position: absolute;
  z-index: 60;
  top: calc(100% + 6px);
  left: 0;
  box-sizing: border-box;
  width: min(300px, 100%);
  padding: 10px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  box-shadow: 0 8px 26px #0002;
  cursor: default;
}

.popover-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
}

.close {
  padding: 0 4px;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 0;
  font-size: 18px;
  cursor: pointer;
}

.tag-group {
  display: grid;
  gap: 5px;
  margin: 8px 0;
}

.group-label,
.muted,
.empty {
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
}

.load-error {
  color: var(--c-danger, #c33);
  font-size: 11px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.suggestion-list {
  max-height: 104px;
  overflow-y: auto;
  padding-right: 3px;
  scrollbar-width: thin;
}

.suggestion-group {
  margin-top: 8px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  max-width: 100%;
  padding: 2px 6px;
  border-radius: 10px;
  font-size: 11px;
  overflow-wrap: anywhere;
  color: var(--c-text);
  background: var(--c-surface-2);
}

.tag.suggestion {
  border: 1px solid var(--c-border);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}

.tag.suggestion:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.tag button {
  padding: 0;
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.tag button:disabled {
  opacity: 0.5;
}

.add-form {
  display: flex;
  gap: 5px;
  margin-top: 10px;
}

.add-form input {
  min-width: 0;
  flex: 1;
  padding: 5px 7px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 5px;
  font-size: 12px;
}

.add-form button {
  padding: 4px 8px;
  color: var(--c-text);
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: 5px;
  cursor: pointer;
}

.add-form button.auto {
  color: var(--accent, #6258d5);
  border-color: color-mix(in srgb, var(--accent, #6258d5) 35%, var(--c-border));
}

.add-form button:disabled {
  opacity: 0.5;
  cursor: default;
}

.muted,
.error {
  margin: 7px 0 0;
}

.error {
  color: var(--c-danger, #c33);
  font-size: 11px;
}
</style>
