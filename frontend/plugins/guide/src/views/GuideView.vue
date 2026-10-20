<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  host,
  pluginApi,
  type PluginInfo,
} from '@thisnote/plugin-api'
import {
  guideApi,
  type GuideEnvironment,
} from '../api/guideApi'
import createGroupImage from '../assets/create-group.png?inline'
import createNoteImage from '../assets/create-note.png?inline'
import reviewImage from '../assets/review.png?inline'

const STEP_KEY = 'thisnote.guide.step'
const MANUAL_PREFIX = 'thisnote.guide.task.'

type Section = 'basic' | 'optional' | 'advanced'
type TaskAction =
  | { kind: 'manual'; label: string }
  | { kind: 'route'; label: string; route: string }
  | { kind: 'open-favorites'; label: string }

interface GuideTask {
  id: string
  section: Section
  title: string
  description: string
  done: boolean
  detail?: string
  action?: TaskAction
}

const plugins = ref<PluginInfo[]>([])
const environment = ref<GuideEnvironment>({ done: false, detail: '正在检查数据目录…' })
const loading = ref(true)
const manual = reactive<Record<string, boolean>>({})

const activeIds = computed(() => new Set(plugins.value.filter((plugin) => plugin.active).map((plugin) => plugin.id)))

const pluginsReviewed = computed(() => manualDone('plugins'))
const searchReviewed = computed(() => manualDone('search'))

const basicTasks = computed<GuideTask[]>(() => [
  {
    id: 'environment',
    section: 'basic',
    title: '认识数据模型与环境',
    description: '数据按「分组 → 笔记 → 卡片」组织；卡片是页面里的独立内容块，正文使用 Markdown。数据目录由启动配置决定，界面不支持修改。',
    done: environment.value.done,
    detail: environment.value.detail,
  },
  {
    id: 'plugins',
    section: 'basic',
    title: '选择插件并配置能力',
    description: '在设置页的插件管理中完成插件启停和能力配置。AI 调用会把相关笔记文字或图片发送给 Provider，并可能产生费用。',
    done: pluginsReviewed.value,
    detail: `${plugins.value.filter((plugin) => plugin.active).length} 个插件正在运行`,
    action: { kind: 'route', label: '前往设置', route: '/settings?tab=plugins' },
  },
  {
    id: 'group-note',
    section: 'basic',
    title: '创建分组和笔记',
    description: '先点击侧栏「全部笔记」右侧红框中的 + 创建分组；再点击笔记列表顶部的 + 创建笔记，并在右侧标题位置填写名称。',
    done: manualDone('group-note'),
    action: { kind: 'route', label: '打开笔记', route: '/notes' },
  },
  {
    id: 'search',
    section: 'basic',
    title: '搜索与导航',
    description: '侧栏用于切换全部笔记、分组和插件入口；顶栏搜索当前按笔记标题匹配。',
    done: searchReviewed.value,
    action: { kind: 'manual', label: '标记已了解' },
  },
])

const optionalTasks = computed<GuideTask[]>(() => {
  const tasks: GuideTask[] = []
  if (activeIds.value.has('favorite')) {
    tasks.push({
      id: 'favorite',
      section: 'optional',
      title: '收藏笔记',
      description: '收藏是笔记列表的筛选条件，不是独立页面；先收藏一篇笔记，再从侧栏打开「收藏」。',
      done: manualDone('favorite'),
      action: { kind: 'open-favorites', label: '打开收藏筛选' },
    })
  }
  if (activeIds.value.has('memory-cycle')) {
    tasks.push({
      id: 'review',
      section: 'optional',
      title: '复习',
      description: '点击卡片右上角红框中的记忆周期按钮开启复习，再从侧栏红框中的「复习」进入今日队列。',
      done: manualDone('review'),
      action: { kind: 'route', label: '打开复习', route: '/review' },
    })
  }
  if (activeIds.value.has('tags')) {
    tasks.push({
      id: 'tags',
      section: 'optional',
      title: '标签',
      description: '标签保存在独立插件中；本地文本也能生成标签，配置 AI 后可使用自动标签增强。',
      done: manualDone('tags'),
      action: { kind: 'route', label: '打开标签页', route: '/tags' },
    })
  }
  if (activeIds.value.has('graphify')) {
    tasks.push({
      id: 'graphify',
      section: 'optional',
      title: '知识图谱',
      description: '先完成 Graphify 配置和索引，再查看图谱；索引可能调用 Provider 并产生费用，不会自动启动。',
      done: manualDone('graphify'),
      action: { kind: 'route', label: '打开知识图谱', route: '/graphify' },
    })
  }
  return tasks
})

const advancedTasks = computed<GuideTask[]>(() => {
  const tasks: GuideTask[] = [
    {
      id: 'data-safety',
      section: 'advanced',
      title: '数据安全与网盘同步',
      description: '本地文件是唯一权威；网盘同步用于保持文件一致，不是历史版本备份。',
      done: manualDone('data-safety'),
      action: { kind: 'manual', label: '标记已了解' },
    },
  ]
  if (activeIds.value.has('card-importer') || activeIds.value.has('attachment-importer')) {
    tasks.push({
      id: 'importers',
      section: 'advanced',
      title: '自动导入器',
      description: '自动导入器会在后台把无主卡片或未引用图片变成笔记；启用后请留意它们的设置。',
      done: manualDone('importers'),
      action: { kind: 'manual', label: '标记已了解' },
    })
  }
  tasks.push({
    id: 'danger',
    section: 'advanced',
    title: '删除与外部编辑',
    description: '删除笔记是永久操作，当前没有回收站；外部编辑器修改卡片时，thisnote 以磁盘内容为准，不会静默覆盖。',
    done: manualDone('danger'),
    action: { kind: 'manual', label: '标记已了解' },
  })
  if (activeIds.value.has('baidu-sync')) {
    tasks[0].action = { kind: 'route', label: '打开网盘同步', route: '/baidu-sync' }
  }
  return tasks
})

const basicProgress = computed(() => progressOf(basicTasks.value))
const optionalProgress = computed(() => progressOf(optionalTasks.value))
const stepTasks = computed<GuideTask[]>(() => [
  ...basicTasks.value,
  ...optionalTasks.value,
  ...advancedTasks.value,
])
const currentStepId = ref(localStorage.getItem(STEP_KEY) ?? '')
const currentStepIndex = computed(() => {
  const index = stepTasks.value.findIndex((task) => task.id === currentStepId.value)
  return index >= 0 ? index : 0
})
const currentTask = computed(() => stepTasks.value[currentStepIndex.value] ?? null)
const currentSectionLabel = computed(() => currentTask.value ? sectionName(currentTask.value.section) : '')

function sectionName(section: Section) {
  switch (section) {
    case 'basic': return '基础上手'
    case 'optional': return '可选功能'
    case 'advanced': return '进阶帮助'
    default: return ''
  }
}
const canPrevious = computed(() => currentStepIndex.value > 0)
const canNext = computed(() => currentStepIndex.value < stepTasks.value.length - 1)
const manualAction = computed(() =>
  currentTask.value?.action?.kind === 'manual' ? currentTask.value.action : null,
)
const footerAction = computed(() =>
  currentTask.value?.action && currentTask.value.action.kind !== 'manual'
    ? currentTask.value.action
    : null,
)

watch(
  stepTasks,
  (tasks) => {
    if (!tasks.length) return
    if (tasks.some((task) => task.id === currentStepId.value)) return
    const saved = localStorage.getItem(STEP_KEY)
    const target = tasks.find((task) => task.id === saved)
      ?? tasks.find((task) => !task.done)
      ?? tasks[0]
    currentStepId.value = target.id
    localStorage.setItem(STEP_KEY, target.id)
  },
  { immediate: true },
)

function progressOf(tasks: GuideTask[]) {
  return {
    done: tasks.filter((task) => task.done).length,
    total: tasks.length,
  }
}

function selectStep(id: string) {
  currentStepId.value = id
  localStorage.setItem(STEP_KEY, id)
}

function previousStep() {
  const previous = stepTasks.value[currentStepIndex.value - 1]
  if (previous) selectStep(previous.id)
}

async function nextStep() {
  const next = stepTasks.value[currentStepIndex.value + 1]
  if (next) {
    selectStep(next.id)
    return
  }
  openBrowserTab('/notes')
}

function manualKey(id: string) {
  return MANUAL_PREFIX + id
}

function manualDone(id: string) {
  return manual[id] === true
}

function markManual(id: string) {
  manual[id] = true
  localStorage.setItem(manualKey(id), '1')
}

function loadManual() {
  for (const id of [
    'plugins', 'group-note', 'search',
    'favorite', 'review', 'tags', 'graphify', 'data-safety', 'importers', 'danger',
  ]) {
    manual[id] = localStorage.getItem(manualKey(id)) === '1'
  }
}

async function refresh() {
  loading.value = true
  try {
    plugins.value = await pluginApi.list()
    environment.value = await guideApi.environment()
  } catch (e) {
    host().notify(`读取环境状态失败：${(e as Error).message}`, 'error')
  } finally {
    loading.value = false
  }
}

function go(path: string, taskId?: string) {
  if (taskId) markManual(taskId)
  openBrowserTab(path)
}

function openBrowserTab(path: string) {
  const base = window.location.href.split('#')[0]
  window.open(`${base}#${path}`, '_blank', 'noopener,noreferrer')
}

async function runAction(task: GuideTask) {
  if (!task.action) return
  switch (task.action.kind) {
    case 'manual':
      markManual(task.id)
      break
    case 'route':
      markManual(task.id)
      openBrowserTab(task.action.route)
      break
    case 'open-favorites':
      markManual('favorite')
      openBrowserTab('/notes')
      break
  }
}

onMounted(async () => {
  loadManual()
  await refresh()
})
</script>

<template>
  <div class="guide-view">
    <header class="guide-head">
      <div>
        <p class="eyebrow">thisnote / getting started</p>
        <h1>从这里开始</h1>
        <p class="lead">先完成一条真实笔记的闭环，再按需了解插件和进阶能力。指南不会自动修改你的数据。</p>
      </div>
      <div class="head-actions">
        <button class="btn ghost" @click="go('/notes')">进入笔记</button>
        <button class="btn ghost" :disabled="loading" @click="refresh">刷新状态</button>
      </div>
    </header>

    <div class="progress-strip">
      <div>
        <span class="progress-label">基础上手</span>
        <strong>{{ basicProgress.done }} / {{ basicProgress.total }}</strong>
      </div>
      <div>
        <span class="progress-label">可选功能</span>
        <strong>{{ optionalProgress.done }} / {{ optionalProgress.total }}</strong>
      </div>
      <span class="progress-hint">进阶帮助不阻塞基础进度</span>
    </div>

    <main class="guide-content wizard-layout">
      <nav class="step-rail" aria-label="指南步骤">
        <div class="rail-head">
          <span>步骤</span>
          <strong>{{ stepTasks.length }}</strong>
        </div>
        <button
          v-for="(task, index) in stepTasks"
          :key="task.id"
          type="button"
          class="rail-step"
          :class="{ active: task.id === currentTask?.id, done: task.done }"
          @click="selectStep(task.id)"
        >
          <span class="rail-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="rail-copy">
            <small>{{ sectionName(task.section) }}</small>
            <strong>{{ task.title }}</strong>
          </span>
          <span v-if="task.done" class="rail-check">✓</span>
        </button>
      </nav>

      <section v-if="currentTask" class="step-panel">
        <header class="step-head">
          <div class="step-meta">
            <span>{{ currentSectionLabel }}</span>
            <span>第 {{ currentStepIndex + 1 }} / {{ stepTasks.length }} 步</span>
            <span v-if="currentTask.done" class="state done">已完成</span>
            <span v-else class="state">待完成</span>
            <button
              v-if="manualAction && !currentTask.done"
              type="button"
              class="btn sm ghost mark-known"
              @click="runAction(currentTask)"
            >
              标记已了解
            </button>
          </div>
          <h2>{{ currentTask.title }}</h2>
          <p>{{ currentTask.description }}</p>
          <p v-if="currentTask.detail" class="task-detail">{{ currentTask.detail }}</p>
        </header>

        <div class="step-body">
          <div v-if="currentTask.id === 'group-note'" class="guide-shot-pair">
            <figure class="guide-shot">
              <img :src="createGroupImage" alt="侧栏全部笔记右侧的新建分组按钮" />
              <figcaption>先在侧栏红框位置创建分组，新分组会显示在「全部笔记」下方。</figcaption>
            </figure>
            <figure class="guide-shot">
              <img :src="createNoteImage" alt="笔记列表顶部的新建按钮和右侧标题输入位置" />
              <figcaption>再点列表顶部红框中的 + 创建笔记，并在右侧标题位置填写名称。</figcaption>
            </figure>
          </div>

          <figure v-if="currentTask.id === 'review'" class="guide-shot">
            <img :src="reviewImage" alt="卡片右上角的记忆周期按钮和侧栏复习入口" />
            <figcaption>红框分别标出卡片上的开启位置和侧栏复习入口；开启后从侧栏进入复习。</figcaption>
          </figure>

          <p v-if="currentTask.id === 'plugins'" class="settings-hint">
            设置页会把插件启停与对应设置面板放在同一页；保存配置后返回指南即可继续下一步。
          </p>

        </div>

        <footer class="wizard-nav">
          <button type="button" class="btn ghost" :disabled="!canPrevious" @click="previousStep">
            上一步
          </button>
          <span class="wizard-position">{{ currentStepIndex + 1 }} / {{ stepTasks.length }}</span>
          <div class="wizard-next">
            <button
              v-if="footerAction"
              type="button"
              class="btn ghost"
              @click="runAction(currentTask)"
            >
              {{ footerAction.label }}
            </button>
            <button type="button" class="btn primary" @click="nextStep">
              {{ canNext ? '下一步' : '完成并进入笔记' }}
            </button>
          </div>
        </footer>
      </section>
    </main>

  </div>
</template>

<style scoped>
.guide-view {
  min-height: 100%;
  overflow-y: auto;
  color: var(--c-text);
  background: var(--c-bg);
}
.guide-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 30px clamp(18px, 4vw, 54px) 22px;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
}
.eyebrow {
  margin: 0 0 7px;
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.guide-head h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
}
.head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.lead {
  max-width: 700px;
  margin: 8px 0 0;
  color: var(--c-text-2);
  font-size: 14px;
  line-height: 1.7;
}
.progress-strip {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 10px clamp(18px, 4vw, 54px);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface-2);
}
.progress-strip > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.progress-label,
.progress-hint {
  color: var(--c-text-3);
  font-size: 12px;
}
.progress-strip strong {
  color: var(--accent);
  font-size: 14px;
}
.progress-hint {
  margin-left: auto;
}
.guide-content {
  max-width: 1180px;
  margin: 0 auto;
  padding: 26px clamp(18px, 4vw, 54px) 80px;
}
.wizard-layout {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  align-items: start;
  gap: 22px;
}
.step-rail {
  position: sticky;
  top: 14px;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface);
}
.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text-3);
  font-size: 12px;
}
.rail-head strong {
  color: var(--accent);
}
.rail-step {
  display: grid;
  grid-template-columns: 27px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-bottom: 1px solid color-mix(in srgb, var(--c-border) 55%, transparent);
  background: transparent;
  color: var(--c-text-2);
  text-align: left;
  cursor: pointer;
}
.rail-step:last-child {
  border-bottom: 0;
}
.rail-step:hover {
  background: var(--c-surface-2);
}
.rail-step.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.rail-index {
  color: var(--c-text-3);
  font: 10px/1 var(--font-mono, monospace);
}
.rail-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.rail-copy small {
  color: var(--c-text-3);
  font-size: 10px;
}
.rail-copy strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rail-check {
  color: var(--success);
  font-size: 11px;
}
.step-panel {
  display: flex;
  min-height: 560px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface);
}
.step-head {
  padding: 24px 26px 18px;
  border-bottom: 1px solid var(--c-border);
}
.step-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--c-text-3);
  font-size: 11.5px;
}
.step-meta > span:first-child {
  color: var(--accent);
  font-weight: 600;
}
.step-head h2 {
  margin: 12px 0 0;
  font-size: 22px;
}
.step-head > p {
  margin: 8px 0 0;
  color: var(--c-text-2);
  font-size: 14px;
  line-height: 1.7;
}
.step-body {
  flex: 1;
  padding: 18px 26px 26px;
}
.wizard-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid var(--c-border);
  background: var(--c-surface-2);
}
.wizard-position {
  color: var(--c-text-3);
  font-size: 12px;
}
.wizard-next {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  flex-wrap: wrap;
}
.task-detail {
  color: var(--c-text-3) !important;
  font-size: 12.5px !important;
}
.state {
  padding: 2px 7px;
  border: 1px solid var(--c-border);
  border-radius: 999px;
  color: var(--c-text-3);
  font-size: 11px;
  white-space: nowrap;
}
.state.done {
  border-color: color-mix(in srgb, var(--success) 35%, var(--c-border));
  color: var(--success);
}
.settings-hint {
  max-width: 680px;
  margin: 0;
  padding: 12px 14px;
  border-left: 3px solid var(--accent);
  background: var(--c-surface-2);
  color: var(--c-text-3);
  font-size: 13px;
  line-height: 1.7;
}
.guide-shot-pair {
  display: grid;
  grid-template-columns: minmax(220px, 0.8fr) minmax(360px, 1.6fr);
  align-items: start;
  gap: 12px;
  max-width: 820px;
  margin-bottom: 18px;
}
.guide-shot-pair .guide-shot {
  max-width: none;
  margin: 0;
}
.guide-shot {
  max-width: 820px;
  margin: 0 0 18px;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface-2);
}
.guide-shot img {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
}
.guide-shot figcaption {
  padding: 9px 12px;
  border-top: 1px solid var(--c-border);
  color: var(--c-text-3);
  font-size: 12px;
  line-height: 1.6;
}
.small {
  font-size: 12px;
}
.muted {
  color: var(--c-text-3);
}
@media (max-width: 760px) {
  .guide-head {
    flex-direction: column;
    gap: 12px;
  }
  .progress-strip {
    flex-wrap: wrap;
    gap: 10px 20px;
  }
  .progress-hint {
    width: 100%;
    margin-left: 0;
  }
  .wizard-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .step-rail {
    position: static;
    display: flex;
    overflow-x: auto;
  }
  .rail-head {
    display: none;
  }
  .rail-step {
    min-width: 180px;
    border-right: 1px solid color-mix(in srgb, var(--c-border) 55%, transparent);
    border-bottom: 0;
  }
  .step-panel {
    min-height: 460px;
  }
  .step-head {
    padding: 18px 16px 14px;
  }
  .step-head h2 {
    font-size: 20px;
  }
  .step-body {
    padding: 14px 16px 18px;
  }
  .wizard-nav {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .wizard-position {
    order: -1;
    width: 100%;
    text-align: center;
  }
  .wizard-next {
    flex: 1;
    justify-content: flex-end;
  }
  .guide-shot-pair {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
