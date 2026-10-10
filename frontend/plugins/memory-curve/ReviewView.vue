<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatInterval, loadCardContent, memoryCurveApi, type CardContent, type DueCard, type Grade } from './memoryCurveApi'
import { host, isPluginActive, refreshPluginAvailability } from '@thisnode/plugin-api'

const PLUGIN_ID = 'memory-curve'

/**
 * 复习页：一次性展示全部到期卡片——完整内容直接呈现，每张卡下面是四档评分，
 * 按钮上标注这一次评分后的下一次复习间隔（与真实调度同一套计算）。
 *
 * 布局为多列瀑布流（列宽自适应），屏幕内尽可能多地展示卡片。
 * 评分两条路：单张（面板下方按钮）或勾选多张批量评分——每张卡各自进入自己的时间周期，
 * 评分成功后从视图移除。键盘 1-4 作用于列表最上方的卡片。
 */
const router = useRouter()
const { notify, MarkdownView } = host()
const queue = ref<Array<DueCard & Partial<CardContent>>>([])
const loading = ref(true)
const reviewedCount = ref(0)
const busyIds = ref(new Set<string>())
const selected = ref(new Set<string>())
const batchBusy = ref(false)

const stateLabel = (state: string) => {
  switch (state) {
    case 'review': return '复习'
    case 'learning': return '学习中'
    case 'relearning': return '重学中'
    default: return '新卡片'
  }
}

const intervalOf = (card: DueCard, g: Grade) => {
  const at = card.gradePreview?.[String(g)]
  return at ? formatInterval(at) : ''
}

const allSelected = computed(() =>
  queue.value.length > 0 && queue.value.every((c) => selected.value.has(c.cardId)),
)

function toggleSelect(cardId: string) {
  const next = new Set(selected.value)
  if (next.has(cardId)) next.delete(cardId)
  else next.add(cardId)
  selected.value = next
}

function toggleSelectAll() {
  selected.value = allSelected.value
    ? new Set()
    : new Set(queue.value.map((c) => c.cardId))
}

/** 拼不出内容的排期条数（卡片已被删、排期还在的孤儿）。 */
const orphanCount = ref(0)

async function loadQueue() {
  loading.value = true
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
    selected.value = new Set()
  } finally {
    loading.value = false
  }
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

/** 评分成功后把它从视图里移除：它已进入自己的时间周期（最快 1 分钟后重现）。 */
function removeFromView(cardId: string) {
  queue.value = queue.value.filter((c) => c.cardId !== cardId)
  const next = new Set(selected.value)
  next.delete(cardId)
  selected.value = next
  reviewedCount.value++
}

async function grade(card: DueCard, g: Grade) {
  if (busyIds.value.has(card.cardId)) return
  busyIds.value.add(card.cardId)
  try {
    await memoryCurveApi.review(card.cardId, g)
    removeFromView(card.cardId)
  } finally {
    busyIds.value.delete(card.cardId)
  }
}

/** 批量评分：逐张独立走 SM-2，各自进入自己的时间周期；失败的卡片留在列表里。 */
async function gradeSelected(g: Grade) {
  const cards = queue.value.filter((c) => selected.value.has(c.cardId))
  if (!cards.length || batchBusy.value) return
  batchBusy.value = true
  let failed = 0
  try {
    for (const card of cards) {
      busyIds.value.add(card.cardId)
      try {
        await memoryCurveApi.review(card.cardId, g)
        removeFromView(card.cardId)
      } catch {
        failed++
        notify(`「${(card.cardTitle || card.noteTitle || '卡片').slice(0, 12)}」评分失败`, 'error')
      } finally {
        busyIds.value.delete(card.cardId)
      }
    }
  } finally {
    batchBusy.value = false
  }
  if (!failed && cards.length > 1) {
    notify(`已批量评分 ${cards.length} 张`, 'success')
  }
}

function onKeydown(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t && ['INPUT', 'TEXTAREA'].includes(t.tagName)) return
  if (e.key === 'Escape') {
    void router.push('/notes')
    return
  }
  const first = queue.value[0]
  if (first && ['1', '2', '3', '4'].includes(e.key) && !batchBusy.value) {
    e.preventDefault()
    void grade(first, Number(e.key) as Grade)
  }
}

onMounted(async () => {
  // 先确认插件还在运行：停用时页面要给出解释，而不是去取一份注定 409 的队列
  await refreshPluginAvailability()
  if (isPluginActive(PLUGIN_ID)) {
    void loadQueue()
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
      <span v-if="queue.length" class="rv-progress">{{ queue.length }} 张待复习</span>
      <div class="spacer"></div>
      <button
        v-if="queue.length"
        class="btn sm ghost"
        @click="toggleSelectAll"
      >
        {{ allSelected ? '取消全选' : '全选' }}
      </button>
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

    <div v-else-if="loading" class="rv-state small muted">加载到期卡片…</div>

    <div v-else-if="!queue.length" class="rv-state rv-done">
      <div class="rv-done-icon">🎉</div>
      <h3>没有到期的卡片</h3>
      <p class="small muted">
        <template v-if="reviewedCount">本次已复习 {{ reviewedCount }} 张，每张都按自己的记忆曲线安排了下一次。</template>
        <template v-else>开启记忆曲线的卡片到期后会出现在这里。</template>
      </p>
      <button class="btn primary" @click="router.push('/notes')">去浏览笔记</button>
    </div>

    <template v-else>
      <p class="small muted rv-tip-top">
        逐张自测后单张评分，或勾选多张批量评分——每张卡各自进入自己的时间周期。按钮上标示评分后的下次复习间隔；键盘 <span class="kbd">1</span>-<span class="kbd">4</span> 作用于最上方的卡片。
      </p>
      <p v-if="orphanCount" class="small muted rv-tip-orphan">
        另有 {{ orphanCount }} 张卡片的笔记已被删除，已跳过（它们不会再出现在这里）。
      </p>

      <!-- 多列瀑布流：列宽自适应，屏幕内尽可能多地展示卡片 -->
      <div class="rv-grid">
        <div
          v-for="(card, i) in queue"
          :key="card.cardId"
          class="rv-card"
          :class="{ 'rv-first': i === 0, 'rv-selected': selected.has(card.cardId) }"
        >
          <div class="rv-meta small muted">
            <input
              type="checkbox"
              class="rv-check"
              :checked="selected.has(card.cardId)"
              aria-label="选中这张卡片"
              @click.stop
              @pointerdown.stop
              @change="toggleSelect(card.cardId)"
            />
            <span class="rv-note-title">{{ card.noteTitle || '' }}</span>
            <span class="rv-state-chip">{{ stateLabel(card.state) }}</span>
          </div>

          <div class="rv-content">
            <component :is="MarkdownView" :content="card.contentMd || ''" />
          </div>

          <div class="rv-actions">
            <button
              v-for="g in GRADES"
              :key="g.grade"
              class="btn rv-grade-btn"
              :class="{ 'rv-good': g.grade === 3 }"
              :disabled="busyIds.has(card.cardId)"
              :title="`评分后 ${intervalOf(card, g.grade)} 内再次复习`"
              @click="grade(card, g.grade)"
            >
              <span class="rv-grade-label">{{ g.label }}</span>
              <span class="rv-grade-interval">{{ intervalOf(card, g.grade) }}</span>
            </button>
            <span v-if="i === 0" class="small muted rv-kbd-hint">← 键盘 1-4</span>
          </div>
        </div>
      </div>

      <!-- 批量评分条：勾选后出现，吸底常驻 -->
      <div class="rv-batch" :class="{ active: selected.size > 0 }">
        <label class="rv-batch-select small">
          <input
            type="checkbox"
            :checked="allSelected"
            :disabled="batchBusy"
            @change="toggleSelectAll"
          />
          全选
        </label>
        <span class="small">{{ selected.size ? `已选 ${selected.size} 张` : '勾选卡片后可批量评分' }}</span>
        <div class="spacer"></div>
        <button
          v-for="g in GRADES"
          :key="g.grade"
          class="btn sm"
          :class="{ 'rv-good': g.grade === 3 }"
          :disabled="selected.size === 0 || batchBusy"
          :title="`批量「${g.label}」：${selected.size || 0} 张各自进入自己的时间周期`"
          @click="gradeSelected(g.grade)"
        >
          {{ g.label }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.review-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--space-4);
  padding-bottom: 64px; /* 给吸底批条留出空间 */
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

/* 多列瀑布流：列宽 320px 自适应，屏幕越宽同屏卡片越多 */
.rv-grid {
  columns: 320px;
  column-gap: var(--space-3);
}
.rv-card {
  display: inline-flex;
  width: 100%;
  margin: 0 0 var(--space-3);
  break-inside: avoid;
  flex-direction: column;
  gap: var(--space-2);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 12px;
  padding: var(--space-3);
  box-shadow: var(--shadow-sm);
}
.rv-card.rv-first {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.rv-card.rv-selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 22%, transparent);
}
.rv-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.rv-note-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rv-check {
  cursor: pointer;
}
.rv-state-chip {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--c-surface-2);
  white-space: nowrap;
}
.rv-content {
  border-top: 1px solid var(--c-border);
  padding-top: var(--space-2);
  min-height: 24px;
  overflow-x: hidden;
}
.rv-actions {
  display: flex;
  gap: 4px;
  align-items: stretch;
  padding-top: var(--space-1);
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
.rv-kbd-hint {
  margin-left: auto;
  align-self: center;
  white-space: nowrap;
}
.rv-batch {
  position: sticky;
  bottom: 0;
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 10px var(--space-4);
  border-radius: 12px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow-lg);
}
.rv-batch.active {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.rv-batch-select {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
</style>
