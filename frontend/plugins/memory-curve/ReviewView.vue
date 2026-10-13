<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  formatInterval,
  loadCardContent,
  loadEnabledCards,
  memoryCurveApi,
  type CardContent,
  type DueCard,
  type EnabledMemoryCard,
  type Grade,
  type MemoryCurveCardState,
} from './memoryCurveApi'
import { memoryCurve } from './index'
import { host, isPluginActive, refreshPluginAvailability } from '@thisnode/plugin-api'

const PLUGIN_ID = 'memory-curve'

/**
 * 复习页：逐张呈现到期卡片，完整内容独立展示；四档评分固定在屏幕下方，
 * 按钮标注下次出现方式（由真实排期预览生成）。
 *
 * 单卡面板居中显示，宽度占可用区域的三分之二。
 * 卡片逐张评分；长周期评分后从视图移除，短周期评分后在队列中随机重排。
 * 键盘 1-4 作用于最前卡片。
 */
const router = useRouter()
const { MarkdownView } = host()
const queue = ref<Array<DueCard & Partial<CardContent>>>([])
const currentCard = computed(() => queue.value[0])
const activeTab = ref<'review' | 'enabled'>('review')
const enabledCards = ref<EnabledMemoryCard[]>([])
const previewCard = ref<EnabledMemoryCard | null>(null)
const enabledCardsLoading = ref(false)
const enabledCardsLoaded = ref(false)
const enabledCardsError = ref('')
const loading = ref(true)
const reviewedCount = ref(0)
const busyIds = ref(new Set<string>())
const reviewedStates = new Map<string, MemoryCurveCardState>()

const stateLabel = (state: string) => {
  switch (state) {
    case 'review': return '复习'
    case 'learning': return '学习中'
    case 'relearning': return '重学中'
    default: return '新卡片'
  }
}

function reminderLabel(card: EnabledMemoryCard) {
  const { dueAt, due } = card.schedule
  if (!dueAt) return '未安排'
  if (due || dueAt <= Date.now()) return '已到期'
  return `下次 ${formatInterval(dueAt)}后`
}

const intervalOf = (card: DueCard, g: Grade) => {
  const at = card.gradePreview?.[String(g)]
  if (!at) return ''
  return at - Date.now() <= 5_000 ? '轮询' : formatInterval(at)
}

function intervalHint(card: DueCard, grade: Grade) {
  const interval = intervalOf(card, grade)
  return interval === '轮询' ? '评分后会在本页队列中轮询重现' : `评分后 ${interval} 再次复习`
}

/** 拼不出内容的排期条数（卡片已被删、排期还在的孤儿）。 */
const orphanCount = ref(0)

async function loadQueue() {
  loading.value = true
  reviewedCount.value = 0
  try {
    const due = await memoryCurveApi.due(50)
    // 正文要自己去核心取：due 只给排期（复习插件不读笔记数据，也就不依赖笔记插件）
    const content = await loadCardContent(due.map((c) => c.cardId))
    // 核心那边已经查不到卡片的排期是**孤儿**（卡片被删，或库被外部重建过）。
    // 必须跳过它们：渲染成一张空白卡只会让人以为复习坏了。
    const live = due.filter((c) => content.has(c.cardId))
    orphanCount.value = due.length - live.length
    // 顺手把孤儿清掉：本插件不读笔记数据，只有「界面侧刚在核心那边查过」这个时点才知道卡片没了。
    // 正常删除由主容器的 CardChangedEvent 兜住，这里补上外部重建/绕过 API 留下的那些。
    for (const gone of due.filter((c) => !content.has(c.cardId))) {
      void memoryCurveApi.forget(gone.cardId).catch(() => {})
    }
    queue.value = live.map((c) => ({ ...c, ...content.get(c.cardId) }))
  } finally {
    loading.value = false
  }
}

async function loadEnabledCardList() {
  if (enabledCardsLoading.value) return
  enabledCardsLoading.value = true
  enabledCardsError.value = ''
  try {
    const loadedCards = await loadEnabledCards()
    for (const card of loadedCards) {
      const reviewed = reviewedStates.get(card.cardId)
      if (reviewed) card.schedule = reviewed.schedule
    }
    enabledCards.value = loadedCards.sort((a, b) => a.schedule.dueAt - b.schedule.dueAt)
    enabledCardsLoaded.value = true
  } catch (error) {
    enabledCardsError.value = error instanceof Error ? error.message : '加载失败，请重试'
  } finally {
    enabledCardsLoading.value = false
  }
}

function selectTab(tab: 'review' | 'enabled') {
  activeTab.value = tab
  if (tab === 'enabled' && !enabledCardsLoaded.value) void loadEnabledCardList()
}

function openPreview(card: EnabledMemoryCard) {
  previewCard.value = card
}

function closePreview() {
  previewCard.value = null
}

/**
 * 插件被全局停用时，本页不能假装「只是没有到期的卡片」——那会误导用户以为一切正常。
 * 直接说清原因并给出恢复入口；同时跳过取数，免得白打一串 409。
 */
const pluginDisabled = computed(() => !isPluginActive(PLUGIN_ID))

async function openPluginSettings() {
  // 直接落到设置页的「插件」标签，别让用户自己找
  await router.push({ path: '/settings', query: { tab: 'plugins' } })
}

/** 长周期评分完成后从本次队列移除。 */
function removeFromView(cardId: string) {
  queue.value = queue.value.filter((c) => c.cardId !== cardId)
  reviewedCount.value++
}

function requeueShort(card: DueCard & Partial<CardContent>, updated: MemoryCurveCardState) {
  const remaining = queue.value.filter((item) => item.cardId !== card.cardId)
  const remainingCount = remaining.length
  if (remainingCount === 0) {
    queue.value = [{
      ...card,
      state: updated.schedule.state,
      dueAt: updated.schedule.dueAt,
      learningStep: updated.learningStep,
      gradePreview: updated.gradePreview ?? card.gradePreview,
    }]
    return
  }

  const minPosition = Math.floor(remainingCount / 5) + 1
  const position = minPosition + Math.floor(Math.random() * (remainingCount - minPosition + 1))
  remaining.splice(position, 0, {
    ...card,
    state: updated.schedule.state,
    dueAt: updated.schedule.dueAt,
    learningStep: updated.learningStep,
    gradePreview: updated.gradePreview ?? card.gradePreview,
  })
  queue.value = remaining
}

function settleGrade(card: DueCard & Partial<CardContent>, updated: MemoryCurveCardState) {
  reviewedStates.set(card.cardId, updated)
  const enabledCard = enabledCards.value.find((item) => item.cardId === card.cardId)
  if (enabledCard) {
    enabledCard.schedule = updated.schedule
    enabledCards.value.sort((a, b) => a.schedule.dueAt - b.schedule.dueAt)
  }
  if (updated.schedule.state === 'learning' || updated.schedule.state === 'relearning') {
    requeueShort(card, updated)
  } else {
    removeFromView(card.cardId)
  }
}

async function grade(card: DueCard, g: Grade) {
  if (busyIds.value.has(card.cardId)) return
  busyIds.value.add(card.cardId)
  try {
    const updated = await memoryCurve.review(card.cardId, g)
    settleGrade(card, updated)
  } finally {
    busyIds.value.delete(card.cardId)
  }
}

function onKeydown(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t && ['INPUT', 'TEXTAREA'].includes(t.tagName)) return
  if (e.key === 'Escape') {
    if (previewCard.value) {
      closePreview()
      return
    }
    void router.push('/notes')
    return
  }
  const first = activeTab.value === 'review' ? currentCard.value : undefined
  if (first && ['1', '2', '3', '4'].includes(e.key)) {
    e.preventDefault()
    void grade(first, Number(e.key) as Grade)
  }
}

onMounted(async () => {
  // 先确认插件还在运行：停用时页面要给出解释，而不是去取一份注定 409 的队列
  await refreshPluginAvailability()
  if (isPluginActive(PLUGIN_ID)) {
    void loadQueue()
    void loadEnabledCardList()
  } else {
    loading.value = false
  }
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

const GRADES: { grade: Grade; label: string }[] = [
  { grade: 1, label: '忘记' },
  { grade: 2, label: '困难' },
  { grade: 3, label: '良好' },
  { grade: 4, label: '简单' },
]
</script>

<template>
  <div class="review-view">
    <header class="rv-head">
      <h2>复习</h2>
      <span v-if="activeTab === 'review' && queue.length" class="rv-progress">{{ queue.length }} 张待复习</span>
      <span v-else-if="activeTab === 'enabled' && enabledCardsLoaded" class="rv-progress">{{ enabledCards.length }} 张已开启</span>
      <span v-else-if="activeTab === 'enabled' && (enabledCardsLoading || loading)" class="rv-progress">统计中…</span>
      <div class="spacer"></div>
      <button class="btn sm ghost" @click="router.push('/notes')">返回笔记</button>
    </header>

    <div v-if="pluginDisabled" class="rv-state rv-done">
      <div class="rv-done-icon">🧠</div>
      <h3>记忆曲线插件已停用</h3>
      <p class="small muted">
        停用期间它自己的端点会被拒绝，所以这里没有可复习的卡片。
        原来开启过的卡片与复习记录都还在，重新启用后照旧。
      </p>
      <button class="btn primary" @click="openPluginSettings">去设置里启用</button>
    </div>

    <div v-if="!pluginDisabled" class="rv-tabs" role="tablist" aria-label="复习内容">
      <button
        class="rv-tab"
        :class="{ active: activeTab === 'review' }"
        role="tab"
        :aria-selected="activeTab === 'review'"
        @click="selectTab('review')"
      >
        卡片复习 <span v-if="queue.length" class="rv-tab-count">{{ queue.length }}</span>
      </button>
      <button
        class="rv-tab"
        :class="{ active: activeTab === 'enabled' }"
        role="tab"
        :aria-selected="activeTab === 'enabled'"
        @click="selectTab('enabled')"
      >
        已开启卡片
        <span v-if="enabledCardsLoaded" class="rv-tab-count">{{ enabledCards.length }}</span>
        <span v-else-if="enabledCardsLoading || loading" class="rv-tab-count">…</span>
      </button>
    </div>

    <div v-if="!pluginDisabled && activeTab === 'review' && loading" class="rv-state small muted">加载到期卡片…</div>

    <div v-else-if="!pluginDisabled && activeTab === 'review' && !queue.length" class="rv-state rv-done">
      <div class="rv-done-icon">🎉</div>
      <h3>没有到期的卡片</h3>
      <p class="small muted">
        <template v-if="reviewedCount">本次已复习 {{ reviewedCount }} 张，每张都按自己的记忆曲线安排了下一次。</template>
        <template v-else>开启记忆曲线的卡片到期后会出现在这里。</template>
      </p>
      <button class="btn primary" @click="router.push('/notes')">去浏览笔记</button>
    </div>

    <template v-else-if="!pluginDisabled && activeTab === 'review'">
      <p class="small muted rv-tip-top">
        逐张自测并单张评分。短周期卡片会在队列中稍后随机重现；按钮显示下次出现方式；键盘 <span class="kbd">1</span>-<span class="kbd">4</span> 作用于当前卡片。
      </p>
      <p v-if="orphanCount" class="small muted rv-tip-orphan">
        另有 {{ orphanCount }} 张卡片的笔记已被删除，已跳过（它们不会再出现在这里）。
      </p>

      <div class="rv-review-stage">
        <div
          v-for="card in queue.slice(0, 1)"
          :key="card.cardId"
          class="rv-card rv-first"
        >
          <div class="rv-content">
            <component :is="MarkdownView" :content="card.contentMd || ''" />
          </div>

        </div>
        <div v-if="currentCard" class="rv-actions rv-review-actions">
          <button
            v-for="g in GRADES"
            :key="g.grade"
            class="btn rv-grade-btn"
            :class="{ 'rv-good': g.grade === 3 }"
            :disabled="busyIds.has(currentCard.cardId)"
            :title="intervalHint(currentCard, g.grade)"
            @click="grade(currentCard, g.grade)"
          >
            <span class="rv-grade-label">{{ g.label }}</span>
            <span class="rv-grade-interval">{{ intervalOf(currentCard, g.grade) }}</span>
          </button>
        </div>
      </div>

    </template>

    <section v-else-if="!pluginDisabled && activeTab === 'enabled'" class="rv-enabled-panel">
      <div class="rv-enabled-header">
        <p class="small muted">已开启记忆周期的卡片（包含未到期卡片）</p>
        <button class="btn sm ghost" :disabled="enabledCardsLoading" @click="loadEnabledCardList">
          {{ enabledCardsLoading ? '加载中…' : '刷新' }}
        </button>
      </div>
      <div v-if="enabledCardsLoading" class="rv-state small muted">加载已开启卡片…</div>
      <div v-else-if="enabledCardsError" class="rv-state rv-done">
        <p class="small muted">加载失败：{{ enabledCardsError }}</p>
        <button class="btn primary" @click="loadEnabledCardList">重试</button>
      </div>
      <div v-else-if="!enabledCardsLoaded" class="rv-state small muted">正在准备卡片列表…</div>
      <div v-else-if="!enabledCards.length" class="rv-state rv-done">
        <h3>没有开启记忆周期的卡片</h3>
      </div>
      <div v-else class="rv-enabled-list">
        <article
          v-for="card in enabledCards"
          :key="card.cardId"
          class="rv-enabled-card"
        >
          <div class="rv-enabled-content">
            <button
              class="btn sm ghost rv-preview-trigger"
              aria-label="预览卡片"
              title="预览卡片"
              @click.stop="openPreview(card)"
            >
              <svg class="rv-preview-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            </button>
            <component :is="MarkdownView" :content="card.contentMd" />
          </div>
        </article>
      </div>
    </section>

    <div v-if="previewCard" class="rv-preview-overlay" @click.self="closePreview">
      <section
        class="rv-preview-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="卡片预览"
      >
        <header class="rv-preview-header">
          <div class="rv-preview-info">
            <span class="small muted">
              {{ stateLabel(previewCard.schedule.state) }} · {{ reminderLabel(previewCard) }}
            </span>
          </div>
          <button class="btn sm ghost" aria-label="关闭预览" @click="closePreview">×</button>
        </header>
        <div class="rv-preview-content">
          <component :is="MarkdownView" :content="previewCard.contentMd" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.review-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--space-4);
  padding-bottom: var(--space-4);
  gap: var(--space-3);
  overflow-y: auto;
}
.rv-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.rv-head h2 {
  margin: 0;
  font-size: 18px;
}
.rv-tabs {
  display: flex;
  gap: var(--space-2);
  border-bottom: 1px solid var(--c-border);
}
.rv-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
}
.rv-tab.active {
  border-bottom-color: var(--accent);
  color: var(--accent);
}
.rv-tab-count {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.rv-enabled-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 0;
}
.rv-progress {
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}
.spacer {
  flex: 1;
}
.rv-tip-top {
  margin: 0;
}
.rv-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  flex: 1;
}
.rv-done-icon {
  font-size: 42px;
}
.rv-enabled-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.rv-enabled-header p {
  flex: 1;
  margin: 0;
}
.rv-enabled-list {
  columns: 3;
  column-gap: var(--space-3);
}
.rv-enabled-card {
  position: relative;
  display: inline-flex;
  box-sizing: border-box;
  width: 100%;
  max-height: 50vh;
  margin: 0 0 var(--space-3);
  break-inside: avoid;
  flex-direction: column;
  gap: var(--space-2);
  overflow: auto;
  padding: var(--space-3);
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-sm);
}
.rv-preview-trigger {
  position: absolute;
  top: var(--space-2);
  right: calc(var(--space-2) + 16px);
  z-index: 1;
}
.rv-preview-icon {
  display: block;
  width: 16px;
  height: 16px;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.rv-enabled-content {
  padding-right: 64px;
  overflow-x: hidden;
}
@media (max-width: 900px) {
  .rv-enabled-list {
    columns: 2;
  }
}
@media (max-width: 600px) {
  .rv-enabled-list {
    columns: 1;
  }
}

.rv-review-stage {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  width: 66.666%;
  margin-inline: auto;
}
.rv-card {
  display: flex;
  width: 100%;
  max-height: 90%;
  margin: 0;
  flex-direction: column;
  overflow-y: auto;
  gap: var(--space-2);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 12px;
  padding: var(--space-3);
  box-shadow: var(--shadow-sm);
}
@media (max-width: 700px) {
  .rv-review-stage {
    width: 100%;
  }
}
.rv-card.rv-first {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.rv-content {
  min-height: 24px;
  overflow-x: hidden;
}
.rv-actions {
  display: flex;
  gap: var(--space-2);
  align-items: stretch;
  padding-top: var(--space-1);
}
.rv-review-actions {
  position: sticky;
  bottom: 0;
  z-index: 100;
  width: min(75%, 760px);
  align-self: center;
  box-sizing: border-box;
  margin-top: auto;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}
/* 评分按钮：两行——档位 + 下次间隔 */
.rv-grade-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 4px 2px;
  min-width: 0;
}
.rv-grade-label {
  font-size: 12px;
}
.rv-grade-interval {
  font-size: 10px;
  color: var(--c-text-3);
  white-space: nowrap;
}
.rv-grade-btn.rv-good .rv-grade-interval {
  color: var(--accent);
}
.rv-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  background: rgb(0 0 0 / 48%);
  overscroll-behavior: contain;
}
.rv-preview-dialog {
  display: flex;
  width: fit-content;
  min-width: min(480px, 90vw);
  max-width: 90vw;
  max-height: 80vh;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: 12px;
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}
.rv-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--c-border);
}
.rv-preview-info {
  min-width: 0;
}
.rv-preview-content {
  min-width: 0;
  max-height: calc(80vh - 64px);
  overflow: auto;
  padding: var(--space-4);
}
</style>
