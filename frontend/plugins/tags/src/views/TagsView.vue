<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { emitPluginStateChange, toast } from '@thisnote/plugin-api'
import { tagsApi, TAGS_PLUGIN_ID } from '../api/tagsApi'
import { activateTagFilter, clearTagFilter, selectedTag } from '../tagFilter'
import { buildTagUsage, loadTagState, noteTags } from '../tagStore'
import TagsBubbleChart from '../components/TagsBubbleChart.vue'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const deleting = ref('')
const query = ref('')
const usageCounts = ref<Record<string, number>>({})
const tags = computed(() =>
  buildTagUsage(noteTags, usageCounts.value).filter((tag) => tag.count > 0),
)
const taggedNoteCount = computed(() => Object.keys(noteTags).length)
const totalOccurrences = computed(() => tags.value.reduce((sum, tag) => sum + tag.count, 0))
const filteredTags = computed(() => {
  const value = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!value) return tags.value
  return tags.value.filter((tag) => tag.label.toLocaleLowerCase('zh-CN').includes(value))
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [, usage] = await Promise.all([loadTagState(true), tagsApi.usage()])
    usageCounts.value = usage
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openTag(tag: string): void {
  activateTagFilter(tag)
  void router.push('/notes')
}

async function removeEverywhere(tag: string): Promise<void> {
  if (deleting.value) return
  if (!window.confirm(`从所有笔记移除标签「${tag}」？`)) return
  deleting.value = tag
  try {
    await tagsApi.removeEverywhere(tag)
    await loadTagState(true)
    if (selectedTag.value === tag) {
      clearTagFilter()
    }
    emitPluginStateChange({ pluginId: TAGS_PLUGIN_ID })
    toast(`已移除标签「${tag}」`, 'success')
  } catch (e) {
    toast(`删除标签失败：${(e as Error).message}`, 'error')
  } finally {
    deleting.value = ''
  }
}

onMounted(() => void load())
</script>

<template>
  <main class="tags-page">
    <header class="page-heading">
      <div>
        <h1>标签</h1>
        <p>{{ tags.length }} 个标签 · {{ totalOccurrences }} 次出现 · {{ taggedNoteCount }} 篇笔记</p>
      </div>
      <div class="heading-actions">
        <input
          v-model="query"
          class="tag-search"
          type="search"
          placeholder="搜索标签"
          aria-label="搜索标签"
        />
        <button type="button" class="refresh" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <p v-if="error" class="feedback error">{{ error }}</p>
    <p v-else-if="loading" class="feedback">正在读取标签…</p>
    <p v-else-if="!tags.length" class="empty">暂无标签</p>
    <p v-else-if="!filteredTags.length" class="empty">没有匹配的标签</p>

    <div v-else class="tag-grid">
      <div v-for="tag in filteredTags" :key="tag.label" class="tag-row">
        <button type="button" class="tag-open" @click="openTag(tag.label)">
          <span class="tag-label">{{ tag.label }}</span>
          <span class="tag-count">{{ tag.count }} 次 · {{ tag.noteCount }} 篇</span>
        </button>
        <button
          type="button"
          class="tag-delete"
          :disabled="deleting === tag.label"
          :aria-label="'删除标签 ' + tag.label"
          title="从所有笔记移除"
          @click="removeEverywhere(tag.label)"
        >×</button>
      </div>
    </div>

    <TagsBubbleChart
      v-if="filteredTags.length"
      :tags="filteredTags"
      @select="openTag"
    />
  </main>
</template>

<style scoped>
.tags-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
  padding: 18px;
  color: var(--c-text);
  overflow: auto;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.page-heading h1 {
  margin: 0 0 5px;
  font-size: 20px;
}

.page-heading p {
  margin: 0;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
}

.heading-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.tag-search {
  width: min(260px, 100%);
  min-height: 30px;
  box-sizing: border-box;
  padding: 4px 9px;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 5px;
  font: inherit;
  font-size: 12px;
}

.refresh {
  padding: 4px 9px;
  color: var(--c-text-2);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: 5px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.refresh:disabled {
  opacity: 0.5;
  cursor: default;
}

.tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 210px), 1fr));
  gap: 7px;
  width: 100%;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 0 2px 0 4px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 6px;
}

.tag-open {
  display: flex;
  flex: 1;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  padding: 8px 2px;
  color: var(--c-text-2);
  text-align: left;
  background: transparent;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.tag-open:hover {
  color: var(--accent);
}

.tag-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.tag-count {
  flex: 0 0 auto;
  color: var(--c-text-muted, #8b9098);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.tag-delete {
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  color: var(--c-text-muted, #8b9098);
  background: transparent;
  border: 0;
  border-radius: 4px;
  font-size: 15px;
  cursor: pointer;
}

.tag-delete:hover {
  color: var(--c-danger, #c33);
  background: color-mix(in srgb, var(--c-danger, #c33) 8%, transparent);
}

@media (max-width: 640px) {
  .page-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .heading-actions {
    justify-content: flex-start;
  }

  .tag-search {
    flex: 1;
    width: auto;
  }
}

.feedback,
.empty {
  margin: 0;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
}

.feedback.error {
  color: var(--c-danger, #c33);
}
</style>
