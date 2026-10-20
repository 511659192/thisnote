<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '@thisnote/plugin-api'
import {
  graphifyApi,
  type GraphifyGraph,
  type GraphifyStatus,
} from '../api/graphifyApi'
import GraphifyTagRanking from '../components/GraphifyTagRanking.vue'
import {
  activateTagFilter,
  buildTagStats,
  selectedTag,
  type GraphifyTagItem,
} from '../tagFilter'

const router = useRouter()
const tagGroups = [
  { id: 'concept' as const, label: '概念', metric: 'edges' as const },
  { id: 'community' as const, label: '社区', metric: 'edges' as const },
]
const activeTagGroup = ref<(typeof tagGroups)[number]['id']>('concept')
const activeTagMetric = 'edges' as const
const status = ref<GraphifyStatus | null>(null)
const graph = ref<GraphifyGraph | null>(null)
const iframe = ref<HTMLIFrameElement | null>(null)
const loading = ref(true)
const requesting = ref(false)
const error = ref('')
const message = ref('')
const iframeRevision = ref(0)
const graphHtmlUrl = computed(() => {
  const url = graphifyApi.graphHtmlUrl()
  return url + (url.includes('?') ? '&' : '?') + 'v=' + iframeRevision.value
})
const nodeCount = computed(() => graph.value?.nodes?.length ?? 0)
const edgeCount = computed(() => graph.value?.links?.length ?? 0)
const tagOverview = computed(() => buildTagStats(graph.value))

let pollTimer: number | null = null
let lastLoadedAt: string | null | undefined
let statusRequestVersion = 0

async function loadGraph(): Promise<void> {
  if (!status.value?.graphAvailable) {
    graph.value = null
    return
  }
  try {
    graph.value = await graphifyApi.graph()
    iframeRevision.value++
    lastLoadedAt = status.value.lastCompletedAt
  } catch (e) {
    error.value = '读取 graph.json 失败：' + (e as Error).message
  }
}

async function refreshStatus(): Promise<void> {
  const version = ++statusRequestVersion
  const previous = status.value
  try {
    const latest = await graphifyApi.status()
    if (version !== statusRequestVersion) return
    status.value = latest
    error.value = ''
    const finished = previous?.running === true && !latest.running
    const graphChanged = latest.lastCompletedAt !== lastLoadedAt
    if (latest.graphAvailable && (graph.value === null || graphChanged || finished)) {
      await loadGraph()
    } else if (!latest.graphAvailable) {
      graph.value = null
    }
  } catch (e) {
    if (version === statusRequestVersion) {
      error.value = '读取索引状态失败：' + (e as Error).message
    }
  } finally {
    loading.value = false
  }
}

async function startIndex(force: boolean): Promise<void> {
  if (force && !window.confirm('强制重建会绕过 Graphify 语义缓存，可能重新调用模型并产生 API 费用。继续吗？')) {
    return
  }
  requesting.value = true
  error.value = ''
  message.value = ''
  try {
    await graphifyApi.index(force)
    await refreshStatus()
  } catch (e) {
    error.value = '提交索引任务失败：' + (e as Error).message
  } finally {
    requesting.value = false
  }
}

function selectTag(tag: GraphifyTagItem): void {
  selectedTag.value = activeTagGroup.value + ':' + tag.label
  activateTagFilter()
  void router.push('/notes')
}

async function relabelCommunities(): Promise<void> {
  if (!window.confirm('重新命名社区会调用配置的模型 API，可能产生费用。继续吗？')) return
  requesting.value = true
  error.value = ''
  message.value = ''
  try {
    await graphifyApi.relabelCommunities()
    await refreshStatus()
  } catch (e) {
    error.value = '提交社区命名任务失败：' + (e as Error).message
  } finally {
    requesting.value = false
  }
}

function normalizeSourceFile(sourceFile: string): string {
  return sourceFile.replace(/\\/g, '/').replace(/^\.\/+/, '')
}

async function openNoteFromGraph(event: MessageEvent): Promise<void> {
  const expectedOrigin = new URL(graphifyApi.graphHtmlUrl(), window.location.href).origin
  if (event.origin !== expectedOrigin || event.source !== iframe.value?.contentWindow) return
  const data = event.data as { source?: unknown; type?: unknown; source_file?: unknown } | null
  if (
    !data ||
    data.source !== 'thisnote-graphify-open-note' ||
    data.type !== 'open-note' ||
    typeof data.source_file !== 'string'
  ) return

  const sourceFile = normalizeSourceFile(data.source_file)
  const parts = sourceFile.split('/')
  if (parts.length < 2 || parts.some((part) => part === '.' || part === '..')) return
  const noteId = parts[0]
  if (!/^[0-9a-f-]{36}$/i.test(noteId)) return
  const graphContainsSource = graph.value?.nodes?.some(
    (node) => typeof node.source_file === 'string' && normalizeSourceFile(node.source_file) === sourceFile,
  )
  if (!graphContainsSource) return

  try {
    const note = await http.get<{ noteId?: string }>('/api/notes/get-detail', { noteId })
    if (note?.noteId === noteId) await router.push('/notes/' + encodeURIComponent(noteId))
  } catch {
    message.value = '图谱中的笔记已删除或暂时无法打开。'
  }
}

onMounted(() => {
  window.addEventListener('message', openNoteFromGraph)
  void refreshStatus()
  pollTimer = window.setInterval(() => void refreshStatus(), 2000)
})

onBeforeUnmount(() => {
  window.removeEventListener('message', openNoteFromGraph)
  if (pollTimer !== null) window.clearInterval(pollTimer)
})
</script>

<template>
  <main class="graphify-page">
    <header class="page-heading">
      <div>
        <h1>知识图谱</h1>
        <p>Graphify 从笔记标题、卡片正文和被引用的图片中抽取概念与语义关系。</p>
      </div>
      <div class="heading-actions">
        <button :disabled="requesting || status?.running" @click="startIndex(false)">
          {{ status?.indexed ? '更新图谱' : '开始首次索引' }}
        </button>
        <button :disabled="requesting || status?.running" class="secondary" @click="startIndex(true)">
          强制重建
        </button>
        <button
          :disabled="requesting || status?.running || !status?.graphAvailable"
          class="secondary"
          @click="relabelCommunities"
        >重新命名社区</button>
      </div>
    </header>

    <section class="status-card">
      <div class="status-line">
        <strong>阶段：{{ status?.stage ?? (loading ? '读取状态…' : '状态不可用') }}</strong>
        <span v-if="status?.indexed">最近完成：{{ status.lastCompletedAt || '未知' }}</span>
        <span v-if="status?.queuedNotes">待处理笔记：{{ status.queuedNotes }}</span>
      </div>
      <div
        v-if="status?.running || status?.stage.includes('排队')"
        class="progress-track"
        role="progressbar"
        aria-label="Graphify 索引正在处理"
      >
        <div class="progress-value"></div>
      </div>
      <div class="graph-summary">
        <span>{{ nodeCount }} 个节点</span>
        <span>{{ edgeCount }} 条关系</span>
        <span>{{ status?.indexed ? '已完成索引' : '尚未完成首次索引' }}</span>
      </div>
      <p v-if="status?.running" class="muted">
        {{ status.completedNotes }} / {{ status.totalNotes }} 篇笔记
      </p>
      <ul v-if="status?.warnings.length" class="warnings">
        <li v-for="warning in status.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <p v-if="status?.error" class="feedback error">{{ status.error }}</p>
      <p v-if="error" class="feedback error">{{ error }}</p>
      <p v-if="message" class="feedback">{{ message }}</p>
    </section>

    <section v-if="status?.graphAvailable" class="tag-overview">
      <div class="tag-overview-heading">
        <div>
          <h2>图谱标签概览</h2>
          <p class="muted">概念与社区按关联边数排序。</p>
        </div>
        <strong>{{ tagOverview[activeTagGroup].length }}</strong>
      </div>
      <div class="tag-group-tabs" role="tablist" aria-label="标签类别">
        <button
          v-for="group in tagGroups"
          :key="group.id"
          type="button"
          role="tab"
          :class="{ active: activeTagGroup === group.id }"
          :aria-selected="activeTagGroup === group.id"
          @click="activeTagGroup = group.id"
        >
          {{ group.label }}
          <span>{{ tagOverview[group.id].length }}</span>
        </button>
      </div>
      <GraphifyTagRanking
        :tags="tagOverview[activeTagGroup]"
        :metric="activeTagMetric"
        @select="selectTag"
      />
    </section>

    <section v-if="status?.graphHtmlAvailable" class="graph-frame">
      <iframe
        ref="iframe"
        :src="graphHtmlUrl"
        title="Graphify 交互式知识图谱"
        allow="fullscreen"
      ></iframe>
      <p class="muted">双击图中的笔记节点可打开对应笔记。交互视图从 unpkg 加载 vis-network，需要网络连接。</p>
    </section>

    <section v-else-if="status?.graphAvailable" class="graph-fallback">
      <h2>图数据已生成，交互视图不可用</h2>
      <p>Graphify 未生成 graph.html；图数据仍已保留并可供后续检索。</p>
      <p>节点 {{ nodeCount }} 个，关系 {{ edgeCount }} 条。</p>
    </section>

    <section v-else-if="!loading" class="first-index">
      <h2>还没有图谱</h2>
      <p>先在设置面板中配置 Provider 和 API key，再手动开始首次索引。</p>
    </section>
  </main>
</template>

<style scoped>
.graphify-page {
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
  flex-wrap: wrap;
  gap: 12px;
}

.page-heading h1 {
  margin: 0 0 5px;
  font-size: 20px;
}

.page-heading p,
.muted {
  margin: 0;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
  line-height: 1.5;
}

.heading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.heading-actions button {
  padding: 7px 10px;
  color: #fff;
  background: var(--accent, #6258d5);
  border: 1px solid var(--accent, #6258d5);
  border-radius: 6px;
  cursor: pointer;
}

.heading-actions button.secondary {
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border-color: var(--c-border);
}

.heading-actions button:disabled {
  opacity: 0.5;
  cursor: default;
}

.status-card,
.tag-overview,
.graph-fallback,
.first-index {
  padding: 12px 14px;
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 8px;
}

.tag-overview {
  display: grid;
  gap: 11px;
}

.tag-overview-heading h2 {
  margin: 0 0 4px;
  font-size: 15px;
}

.tag-overview-heading p {
  margin: 0;
}

.tag-overview-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.tag-overview-heading > strong {
  min-width: 30px;
  color: var(--c-text-muted, #8b9098);
  font-size: 13px;
  font-weight: 500;
  text-align: right;
}

.tag-group-tabs {
  display: inline-flex;
  align-self: flex-start;
  gap: 2px;
  padding: 2px;
  background: var(--c-surface-2);
  border-radius: 6px;
}

.tag-group-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 9px;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
  border-radius: 4px;
}

.tag-group-tabs button:hover {
  color: var(--c-text);
}

.tag-group-tabs button.active {
  color: var(--c-text);
  background: var(--c-surface);
  box-shadow: 0 1px 2px color-mix(in srgb, var(--c-text) 12%, transparent);
}

.tag-group-tabs button span {
  font-size: 10px;
  opacity: 0.7;
}

.status-line,
.graph-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.status-line span,
.graph-summary span {
  color: var(--c-text-muted, #8b9098);
}

.graph-summary {
  margin-top: 8px;
}

.progress-track {
  position: relative;
  height: 5px;
  margin-top: 10px;
  overflow: hidden;
  background: var(--c-surface-2);
  border-radius: 4px;
}

.progress-value {
  width: 36%;
  height: 100%;
  background: var(--accent, #6258d5);
  border-radius: inherit;
  animation: graphify-progress 1.15s ease-in-out infinite;
}

@keyframes graphify-progress {
  from {
    transform: translateX(-110%);
  }
  to {
    transform: translateX(290%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .progress-value {
    width: 100%;
    opacity: 0.45;
    animation: none;
  }
}

.warnings {
  margin: 9px 0 0;
  padding-left: 18px;
  color: #9b6a13;
  font-size: 12px;
}

.feedback {
  margin: 8px 0 0;
  font-size: 12px;
}

.error {
  color: var(--c-danger, #c33);
}

.graph-frame {
  display: flex;
  flex: 1 0 560px;
  flex-direction: column;
  min-height: 560px;
}

.graph-frame iframe {
  width: 100%;
  flex: 1;
  min-height: 520px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface, #fff);
}

.graph-frame .muted {
  margin-top: 5px;
}

.graph-fallback h2,
.first-index h2 {
  margin: 0 0 6px;
  font-size: 15px;
}

.graph-fallback p,
.first-index p {
  margin: 5px 0 0;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
}

</style>
