<script setup lang="ts">
import { ref } from 'vue'
import { ConfirmDialog } from '@thisnote/plugin-api'
import { useReviewPage } from '../useReviewPage'
import ReviewCardTile from '../components/ReviewCardTile.vue'
import ReviewContextMenu from '../components/ReviewContextMenu.vue'

const {
  router,
  MarkdownView,
  queue,
  currentCard,
  loadedContentIds,
  contentLoadingCardId,
  contentError,
  activeTab,
  enabledCards,
  previewCard,
  previewTab,
  enabledCardsLoading,
  enabledCardsLoaded,
  enabledCardsError,
  cardTab,
  loading,
  reviewedCount,
  busyIds,
  histories,
  historiesLoaded,
  stateLabel,
  formatHistory,
  reminderLabel,
  intervalOf,
  intervalHint,
  orphanCount,
  dueTodayCards,
  deleteTarget,
  deleting,
  deleteMessage,
  deleteError,
  selectTab,
  openPreview,
  closePreview,
  locateCard,
  requestDeleteCard,
  cancelDeleteCard,
  confirmDeleteCard,
  onPreviewWheel,
  onPreviewMouseDown,
  pluginDisabled,
  openPluginSettings,
  grade,
  GRADES,
} = useReviewPage()

const reviewMenu = ref<{ x: number; y: number; card: { cardId: string } } | null>(null)

function openReviewMenu(event: MouseEvent, card: { cardId: string }) {
  event.preventDefault()
  const width = 180
  const height = 80
  reviewMenu.value = {
    x: Math.min(event.clientX, window.innerWidth - width - 8),
    y: Math.min(event.clientY, window.innerHeight - height - 8),
    card,
  }
}

function closeReviewMenu() {
  reviewMenu.value = null
}

function deleteFromReviewMenu() {
  const card = reviewMenu.value?.card
  reviewMenu.value = null
  if (card) requestDeleteCard(card)
}

function locateFromReviewMenu() {
  const card = reviewMenu.value?.card
  reviewMenu.value = null
  if (card) locateCard(card)
}

function locateCurrentCard() {
  if (currentCard.value) locateCard(currentCard.value)
}

function locatePreviewCard() {
  if (previewCard.value) locateCard(previewCard.value)
}
</script>

<template>
  <div class="review-view">
    <header class="rv-head">
      <h2>复习</h2>
      <span v-if="activeTab === 'review' && queue.length" class="rv-progress">{{ queue.length }} 张待复习</span>
      <span v-else-if="activeTab === 'due' && historiesLoaded" class="rv-progress">{{ dueTodayCards.length }} 张今日到期</span>
      <span v-else-if="activeTab === 'enabled' && enabledCardsLoaded" class="rv-progress">{{ enabledCards.length }} 张已开启</span>
      <span v-else-if="(activeTab === 'due' || activeTab === 'enabled') && (enabledCardsLoading || loading)" class="rv-progress">统计中…</span>
      <div class="spacer"></div>
      <button
        v-if="activeTab === 'review' && currentCard?.noteId"
        class="btn sm ghost"
        @click="locateCurrentCard"
      >定位卡片</button>
      <button class="btn sm ghost" @click="router.push('/notes')">返回笔记</button>
    </header>

    <div v-if="pluginDisabled" class="rv-state rv-done">
      <div class="rv-done-icon">🧠</div>
      <h3>记忆周期插件已停用</h3>
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
        今日复习
        <span v-if="loading" class="rv-tab-count">…</span>
        <span v-else class="rv-tab-count">{{ queue.length }}</span>
      </button>
      <button
        class="rv-tab"
        :class="{ active: activeTab === 'due' }"
        role="tab"
        :aria-selected="activeTab === 'due'"
        @click="selectTab('due')"
      >
        今日到期
        <span v-if="historiesLoaded" class="rv-tab-count">{{ dueTodayCards.length }}</span>
        <span v-else-if="enabledCardsLoading || loading" class="rv-tab-count">…</span>
      </button>
      <button
        class="rv-tab"
        :class="{ active: activeTab === 'enabled' }"
        role="tab"
        :aria-selected="activeTab === 'enabled'"
        @click="selectTab('enabled')"
      >
        开启记忆
        <span v-if="enabledCardsLoaded" class="rv-tab-count">{{ enabledCards.length }}</span>
        <span v-else-if="enabledCardsLoading || loading" class="rv-tab-count">…</span>
      </button>
    </div>

    <p v-if="deleteMessage" class="rv-feedback success">{{ deleteMessage }}</p>
    <p v-if="deleteError" class="rv-feedback error">删除失败：{{ deleteError }}</p>

    <div v-if="!pluginDisabled && activeTab === 'review' && loading" class="rv-state small muted">加载到期卡片…</div>

    <div v-else-if="!pluginDisabled && activeTab === 'review' && !queue.length" class="rv-state rv-done">
      <div class="rv-done-icon">🎉</div>
      <h3>没有到期的卡片</h3>
      <p class="small muted">
        <template v-if="reviewedCount">本次已复习 {{ reviewedCount }} 张，每张都按自己的记忆周期安排了下一次。</template>
        <template v-else>开启记忆周期的卡片到期后会出现在这里。</template>
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
          @contextmenu.prevent="openReviewMenu($event, card)"
        >
          <div class="rv-content">
            <div v-if="contentLoadingCardId === card.cardId" class="rv-state small muted">
              正在读取当前卡片…
            </div>
            <div v-else-if="contentError" class="rv-state small muted">
              <span>读取卡片失败：{{ contentError }}</span>
              <button class="btn sm ghost" @click="loadCurrentCardContent">重试</button>
            </div>
            <component
              v-else-if="loadedContentIds.has(card.cardId)"
              :is="MarkdownView"
              :content="card.contentMd || ''"
            />
          </div>

        </div>
        <div v-if="currentCard" class="rv-actions rv-review-actions">
          <button
            v-for="g in GRADES"
            :key="g.grade"
            class="btn rv-grade-btn"
            :class="{ 'rv-good': g.grade === 3 }"
            :disabled="busyIds.has(currentCard.cardId) || !loadedContentIds.has(currentCard.cardId) || contentLoadingCardId === currentCard.cardId"
            :title="intervalHint(currentCard, g.grade)"
            @click="grade(currentCard, g.grade)"
          >
            <span class="rv-grade-label">{{ g.label }}</span>
            <span class="rv-grade-interval">{{ intervalOf(currentCard, g.grade) }}</span>
          </button>
        </div>
      </div>

    </template>

    <section
      v-else-if="!pluginDisabled && (activeTab === 'due' || activeTab === 'enabled')"
      class="rv-enabled-panel"
    >
      <div class="rv-enabled-header">
        <p class="small muted">{{ cardTab.caption }}</p>
        <div class="rv-columns">
          <button
            v-for="n in [3, 5, 7]"
            :key="n"
            class="btn sm ghost"
            :class="{ active: cardTab.columns === n }"
            :title="`${n} 列`"
            @click="cardTab.setColumns(n)"
          >{{ n }} 列</button>
        </div>
        <button class="btn sm ghost" :disabled="enabledCardsLoading" @click="loadEnabledCardList">
          {{ enabledCardsLoading ? '加载中…' : '刷新' }}
        </button>
      </div>
      <div v-if="cardTab.loading" class="rv-state small muted">{{ cardTab.loadingText }}</div>
      <div v-else-if="enabledCardsError" class="rv-state rv-done">
        <p class="small muted">加载失败：{{ enabledCardsError }}</p>
        <button class="btn primary" @click="loadEnabledCardList">重试</button>
      </div>
      <div v-else-if="!enabledCardsLoaded" class="rv-state small muted">正在准备卡片列表…</div>
      <div v-else-if="!cardTab.cards.length" class="rv-state rv-done">
        <h3>{{ cardTab.emptyTitle }}</h3>
      </div>
      <div v-else class="rv-enabled-list" :style="{ columnCount: cardTab.columns }">
        <ReviewCardTile
          v-for="card in cardTab.cards"
          :key="card.cardId"
          :card="card"
          @preview="openPreview(card)"
          @locate="locateCard(card)"
          @delete="requestDeleteCard(card)"
        />
      </div>
    </section>

    <ReviewContextMenu
      :open="reviewMenu !== null"
      :x="reviewMenu?.x ?? 0"
      :y="reviewMenu?.y ?? 0"
      @locate="locateFromReviewMenu"
      @delete="deleteFromReviewMenu"
      @close="closeReviewMenu"
    />

    <ConfirmDialog
      v-if="deleteTarget"
      :open="true"
      title="删除卡片？"
      message="卡片会从笔记中永久删除；如果它是笔记的唯一卡片，笔记也会一并删除。"
      confirm-label="删除"
      :busy="deleting"
      @cancel="cancelDeleteCard"
      @confirm="confirmDeleteCard"
    />

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
          <div class="rv-preview-actions">
            <button class="btn sm ghost" @click="locatePreviewCard">定位卡片</button>
            <button class="btn sm ghost" aria-label="关闭预览" @click="closePreview">×</button>
          </div>
        </header>
        <div class="rv-preview-tabs" role="tablist" aria-label="预览内容">
          <button
            class="rv-tab"
            :class="{ active: previewTab === 'content' }"
            role="tab"
            :aria-selected="previewTab === 'content'"
            @click="previewTab = 'content'"
          >卡片内容</button>
          <button
            class="rv-tab"
            :class="{ active: previewTab === 'history' }"
            role="tab"
            :aria-selected="previewTab === 'history'"
            @click="previewTab = 'history'"
          >复习历史</button>
        </div>
        <div v-if="previewTab === 'content'" class="rv-preview-content" @wheel="onPreviewWheel" @mousedown="onPreviewMouseDown" @dragstart.prevent>
          <component :is="MarkdownView" :content="previewCard.contentMd" />
        </div>
        <div v-else class="rv-preview-history-body">
          <template v-if="histories[previewCard.cardId]?.length">
            <ol class="rv-history-list">
              <li v-for="entry in histories[previewCard.cardId]" :key="entry.reviewedAt + '-' + entry.grade">
                {{ formatHistory(entry) }}
              </li>
            </ol>
          </template>
          <span v-else class="small muted">还没有复习记录</span>
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
.rv-feedback {
  margin: 0;
  font-size: 12px;
}
.rv-feedback.success {
  color: var(--success);
}
.rv-feedback.error {
  color: var(--danger);
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
  column-gap: var(--space-3);
}
.rv-columns {
  display: flex;
  gap: 6px;
}
.rv-columns .btn.active {
  color: var(--accent);
  border-color: var(--accent);
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
/* 复习卡较宽：图片随卡片宽度自适应，并放宽 MarkdownView 默认的 320px 高度上限 */
.rv-content :deep(.md-preview img) {
  max-width: 100%;
  max-height: 65vh;
  margin-inline: auto;
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
.rv-preview-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.rv-preview-tabs {
  display: flex;
  gap: var(--space-2);
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--c-border);
}
.rv-preview-history-body {
  min-width: 0;
  max-height: calc(80vh - 110px);
  overflow: auto;
  padding: var(--space-4);
  color: var(--c-text-2);
  font-size: 12px;
}
.rv-history-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding-left: 18px;
}
.rv-preview-content {
  min-width: 0;
  max-height: calc(80vh - 64px);
  overflow: auto;
  padding: var(--space-4);
}
/* 预览浮层完全按自然尺寸，超出部分滚动；列表卡仍受列宽约束以免列数多时破版 */
.rv-preview-content :deep(.md-preview img) {
  max-width: none;
  max-height: none;
  cursor: grab;
  user-select: none;
}
.rv-preview-content :deep(.md-preview img.rv-img-grabbing) {
  cursor: grabbing;
}
</style>
