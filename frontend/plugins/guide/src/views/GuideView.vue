<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  host,
  pluginApi,
  setActiveListFilter,
  type PluginInfo,
} from '@thisnote/plugin-api'
import {
  guideApi,
  type GuideEnvironment,
} from '../api/guideApi'
import createGroupImage from '../assets/create-group.png?inline'
import createNoteImage from '../assets/create-note.png?inline'
import reviewImage from '../assets/review.png?inline'
import searchNavImage from '../assets/search-nav.png?inline'
import favoriteNavImage from '../assets/favorite-nav.png?inline'
import settingsPluginsImage from '../assets/settings-plugins.png?inline'
import tagsSettingsImage from '../assets/tags-settings.png?inline'
import graphifySettingsImage from '../assets/graphify-settings.png?inline'
import dataSafetyImage from '../assets/data-safety.png?inline'
import importersImage from '../assets/importers.png?inline'
import dangerImage from '../assets/danger.png?inline'

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
const stepPanelRef = ref<HTMLElement | null>(null)
const imagePreview = ref<{ src: string; alt: string; caption: string } | null>(null)
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

function progressPercent(progress: { done: number; total: number }) {
  return progress.total ? Math.round(progress.done / progress.total * 100) : 0
}

async function selectStep(id: string) {
  currentStepId.value = id
  localStorage.setItem(STEP_KEY, id)
  await nextTick()
  document.querySelector<HTMLElement>(`[data-step-id="${CSS.escape(id)}"]`)?.scrollIntoView({
    behavior: 'smooth',
    block: 'nearest',
    inline: 'center',
  })
  if (window.innerWidth <= 760) stepPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onStepBodyClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const figure = target?.closest<HTMLElement>('.guide-shot')
  const image = figure?.querySelector<HTMLImageElement>('img')
  if (!figure || !image) return
  imagePreview.value = {
    src: image.src,
    alt: image.alt,
    caption: figure.querySelector('figcaption')?.textContent?.trim() ?? '',
  }
  document.body.style.overflow = 'hidden'
}
function closeImagePreview() {
  imagePreview.value = null
  document.body.style.overflow = ''
}

function onGuideKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && imagePreview.value) closeImagePreview()
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
  navigate('/notes')
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
  navigate(path)
}

function navigate(path: string) {
  window.location.hash = path.startsWith('/') ? path : '/' + path
}


async function runAction(task: GuideTask) {
  if (!task.action) return
  switch (task.action.kind) {
    case 'manual':
      markManual(task.id)
      break
    case 'route':
      markManual(task.id)
      navigate(task.action.route)
      break
    case 'open-favorites':
      markManual('favorite')
      setActiveListFilter('favorite', 'favorites')
      navigate('/notes')
      break
  }
}

onMounted(async () => {
  document.addEventListener('keydown', onGuideKeydown)
  loadManual()
  await refresh()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGuideKeydown)
  document.body.style.overflow = ''
})

</script>

<template>
  <div class="guide-view">
    <header class="guide-head">
      <div>
        <p class="eyebrow">thisnote / getting started</p>
        <h1 id="guide-title">从这里开始</h1>
        <p class="lead">先完成一条真实笔记的闭环，再按需了解插件和进阶能力。指南不会自动修改你的数据。</p>
      </div>
      <div class="head-actions">
        <button class="btn ghost" @click="go('/notes')">进入笔记</button>
        <button class="btn ghost" :disabled="loading" @click="refresh">刷新状态</button>
      </div>
    </header>

    <div class="progress-strip">
      <div class="progress-item">
        <div class="progress-row">
          <span class="progress-label">基础上手</span>
          <strong>{{ basicProgress.done }} / {{ basicProgress.total }}</strong>
        </div>
        <div class="progress-bar" aria-hidden="true"><span :style="{ width: progressPercent(basicProgress) + '%' }"></span></div>
      </div>
      <div class="progress-item">
        <div class="progress-row">
          <span class="progress-label">可选功能</span>
          <strong>{{ optionalProgress.done }} / {{ optionalProgress.total }}</strong>
        </div>
        <div class="progress-bar" aria-hidden="true"><span :style="{ width: progressPercent(optionalProgress) + '%' }"></span></div>
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
          :data-step-id="task.id"
        >
          <span class="rail-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="rail-copy">
            <small>{{ sectionName(task.section) }}</small>
            <strong>{{ task.title }}</strong>
          </span>
          <span v-if="task.done" class="rail-check">✓</span>
        </button>
      </nav>

      <section v-if="currentTask" ref="stepPanelRef" class="step-panel">
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

        <div class="step-body" @click="onStepBodyClick">
          <figure v-if="currentTask.id === 'plugins'" class="guide-shot detail">
            <img :src="settingsPluginsImage" alt="插件管理页面中的插件卡片和设置入口" />
            <figcaption>插件管理页集中展示启停状态和设置入口；点击「设置」会展开并定位到对应插件。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'search'" class="guide-shot wide">
            <img :src="searchNavImage" alt="顶栏模糊搜索和列表标题搜索" />
            <figcaption>顶栏用于跨笔记模糊搜索；列表顶部搜索只过滤当前标题列表。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'favorite'" class="guide-shot compact">
            <img :src="favoriteNavImage" alt="侧栏收藏入口" />
            <figcaption>收藏不是独立页面；在笔记行的星标按钮收藏后，从侧栏「收藏」筛选查看。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'tags'" class="guide-shot detail">
            <img :src="tagsSettingsImage" alt="标签插件设置页中的模型和标签字号配置" />
            <figcaption>标签插件设置页可配置 AI completion、标签字号和自动抽取开关。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'graphify'" class="guide-shot detail">
            <img :src="graphifySettingsImage" alt="Graphify 插件设置页" />
            <figcaption>Graphify 使用 AI 插件提供的模型配置；首次索引需要手动启动并可能产生 Provider 费用。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'data-safety'" class="guide-shot detail">
            <img :src="dataSafetyImage" alt="插件管理中的百度网盘同步插件" />
            <figcaption>百度网盘同步用于保持文件一致；本地文件仍是唯一权威，网盘副本不是历史版本备份。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'importers'" class="guide-shot detail">
            <img :src="importersImage" alt="插件管理中的卡片导入和附件导入插件" />
            <figcaption>卡片导入与附件导入在后台运行；启用后请检查扫描间隔和导入目录设置。</figcaption>
          </figure>

          <figure v-if="currentTask.id === 'danger'" class="guide-shot detail">
            <img :src="dangerImage" alt="插件状态重置设置面板" />
            <figcaption>删除和插件状态重置都不可撤销；执行前先确认本地文件与同步基线。</figcaption>
          </figure>

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

    <Teleport to="body">
      <div v-if="imagePreview" class="guide-image-modal" role="dialog" aria-modal="true" @click.self="closeImagePreview">
        <figure class="guide-image-dialog">
          <button type="button" class="guide-image-close" aria-label="关闭图片预览" @click="closeImagePreview">×</button>
          <img :src="imagePreview.src" :alt="imagePreview.alt" />
          <figcaption v-if="imagePreview.caption">{{ imagePreview.caption }}</figcaption>
        </figure>
      </div>
    </Teleport>

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
.progress-item {
  display: grid;
  gap: 4px;
  min-width: 135px;
}
.progress-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.progress-bar {
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--c-border);
}
.progress-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.2s ease;
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
  width: 100%;
  min-height: 0;
  margin: 0;
  padding: 26px clamp(18px, 4vw, 54px) 80px;
  overflow: auto;
}
.wizard-layout {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 14px;
}
.step-rail {
  display: flex;
  align-items: stretch;
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface);
  scrollbar-width: thin;
}
.rail-head {
  display: flex;
  flex: 0 0 86px;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 2px;
  padding: 10px 12px;
  border-right: 1px solid var(--c-border);
  color: var(--c-text-3);
  font-size: 12px;
}
.rail-head strong {
  color: var(--accent);
}
.rail-step {
  display: grid;
  grid-template-columns: 27px minmax(0, 1fr) auto;
  flex: 0 0 170px;
  gap: 8px;
  align-items: center;
  min-height: 56px;
  padding: 8px 10px;
  border: 0;
  border-right: 1px solid color-mix(in srgb, var(--c-border) 55%, transparent);
  background: transparent;
  color: var(--c-text-2);
  text-align: left;
  cursor: pointer;
}
.rail-step:last-child {
  border-right: 0;
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
  min-height: 0;
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
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 14px;
  max-width: 100%;
  margin-bottom: 18px;
}
.guide-shot-pair .guide-shot {
  max-width: none;
  margin: 0;
}
.guide-shot {
  width: 100%;
  max-width: 100%;
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
  max-height: 560px;
  height: auto;
  margin: 0 auto;
  object-fit: contain;
}
.guide-shot.detail {
  width: min(900px, 100%);
  margin-right: auto;
  margin-left: auto;
}

.guide-shot.detail img {
  width: 100%;
  max-height: none;
}

.guide-shot.compact {
  max-width: 360px;
}

.guide-shot.wide img {
  width: 100%;
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
  .guide-content {
    padding: 12px 16px 18px;
  }
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
    gap: 10px;
  }
  .step-rail {
    border-radius: 0;
  }
  .rail-head {
    display: none;
  }
  .rail-step {
    flex-basis: 180px;
  }
  .step-panel {
    min-height: 0;
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
.guide-shot {
  cursor: zoom-in;
}

.guide-shot:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--c-border));
}

.guide-image-modal {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(0 0 0 / 68%);
  backdrop-filter: blur(3px);
}

.guide-image-dialog {
  position: relative;
  display: grid;
  gap: 10px;
  max-width: min(1400px, 94vw);
  max-height: 90vh;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}

.guide-image-dialog img {
  display: block;
  max-width: 100%;
  max-height: calc(90vh - 100px);
  margin: 0 auto;
  object-fit: contain;
}

.guide-image-dialog figcaption {
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}

.guide-image-close {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  width: 32px;
  height: 32px;
  border: 1px solid var(--c-border);
  border-radius: 50%;
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 20px;
  cursor: pointer;
}

</style>
