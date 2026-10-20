import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import {
  formatInterval,
  deleteReviewCard,
  loadCardContent,
  loadEnabledCards,
  memoryCycleApi,
  resolveCardNoteId,
  type CardContent,
  type DueCard,
  type EnabledMemoryCard,
  type Grade,
  type MemoryCycleCardState,
  type ScheduleHistoryEntry,
} from './api/memoryCycleApi'
import { memoryCycle } from './stores/memoryCycle'
import { createPreviewImageInteractions, emitPluginStateChange, host, isPluginActive, refreshPluginAvailability, toast } from '@thisnote/plugin-api'

const PLUGIN_ID = 'memory-cycle'

export function useReviewPage() {
  const router = useRouter()
  const { MarkdownView } = host()
  const queue = ref<Array<DueCard & Partial<CardContent>>>([])
  const currentCard = computed(() => queue.value[0])
  const loadedContentIds = ref(new Set<string>())
  const contentLoadingCardId = ref<string | null>(null)
  const contentError = ref('')
  type ReviewTab = 'review' | 'due' | 'enabled'
  const activeTab = ref<ReviewTab>('review')
  const enabledCards = ref<EnabledMemoryCard[]>([])
  const previewCard = ref<EnabledMemoryCard | null>(null)
  const previewTab = ref<'content' | 'history'>('content')
  const enabledCardsLoading = ref(false)
  const enabledCardsLoaded = ref(false)
  const enabledCardsError = ref('')
  const dueColumns = ref<3 | 5 | 7>(3)
  const enabledColumns = ref<3 | 5 | 7>(5)
  const loading = ref(true)
  const reviewedCount = ref(0)
  const busyIds = ref(new Set<string>())
  const reviewedStates = new Map<string, MemoryCycleCardState>()
  const histories = ref<Record<string, ScheduleHistoryEntry[]>>({})
  const historiesLoaded = ref(false)
  const orphanCount = ref(0)
  const deleteTarget = ref<{ cardId: string } | null>(null)
  const deleting = ref(false)
  const deleteMessage = ref('')
  const deleteError = ref('')

  function startOfToday(): number {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    return today.getTime()
  }

  function startOfTomorrow(): number {
    const tomorrow = new Date()
    tomorrow.setHours(0, 0, 0, 0)
    tomorrow.setDate(tomorrow.getDate() + 1)
    return tomorrow.getTime()
  }

  async function loadHistories() {
    const ids = enabledCards.value.map((card) => card.cardId)
    historiesLoaded.value = false
    if (!ids.length) {
      histories.value = {}
      historiesLoaded.value = true
      return
    }
    const merged: Record<string, ScheduleHistoryEntry[]> = {}
    for (let i = 0; i < ids.length; i += 100) {
      try {
        Object.assign(merged, await memoryCycleApi.histories(ids.slice(i, i + 100)))
      } catch {
        // A failed chunk does not invalidate the others.
      }
    }
    histories.value = merged
    historiesLoaded.value = true
  }

  const dueTodayCards = computed(() => {
    const start = startOfToday()
    const end = startOfTomorrow()
    const isToday = (time: number) => time > 0 && time >= start && time < end
    return enabledCards.value.filter((card) => {
      const entries = histories.value[card.cardId] ?? []
      const latestDue = entries.length ? entries[entries.length - 1].dueAt : card.schedule.dueAt
      if (latestDue > 0 && latestDue < end) return true
      return entries.some((entry) => isToday(entry.dueAt))
    })
  })

  const cardTab = computed(() => {
    if (activeTab.value === 'due') {
      return {
        caption: '今日到期的卡片（含过期；历史上任意一次排期落在今天也算）',
        columns: dueColumns.value,
        setColumns: (columns: 3 | 5 | 7) => (dueColumns.value = columns),
        loading: enabledCardsLoading.value || (enabledCardsLoaded.value && !historiesLoaded.value),
        loadingText: '加载今日到期卡片…',
        cards: dueTodayCards.value,
        emptyTitle: '今天没有到期的卡片',
      }
    }
    return {
      caption: '已开启记忆周期的卡片（包含未到期卡片）',
      columns: enabledColumns.value,
      setColumns: (columns: 3 | 5 | 7) => (enabledColumns.value = columns),
      loading: enabledCardsLoading.value,
      loadingText: '加载已开启卡片…',
      cards: enabledCards.value,
      emptyTitle: '没有开启记忆周期的卡片',
    }
  })

  const stateLabel = (state: string) => {
    switch (state) {
      case 'review': return '复习'
      case 'learning': return '学习中'
      case 'relearning': return '重学中'
      default: return '新卡片'
    }
  }
  const GRADE_LABEL: Record<number, string> = { 0: '开启', 1: '忘记', 2: '困难', 3: '良好', 4: '简单' }

  function formatHistory(entry: ScheduleHistoryEntry): string {
    const at = new Date(entry.reviewedAt)
    const due = new Date(entry.dueAt)
    const pad = (value: number) => value.toString().padStart(2, '0')
    return `${at.getMonth() + 1}/${at.getDate()} ${pad(at.getHours())}:${pad(at.getMinutes())}`
      + ` · ${GRADE_LABEL[entry.grade] ?? entry.grade} → 下次 ${due.getMonth() + 1}/${due.getDate()}`
  }

  function reminderLabel(card: EnabledMemoryCard) {
    const { dueAt, due } = card.schedule
    if (!dueAt) return '未安排'
    if (due) return '今日到期'
    return `下次 ${formatInterval(dueAt)}后`
  }

  const intervalOf = (card: DueCard, grade: Grade) => {
    const at = card.gradePreview?.[String(grade)]
    if (!at) return ''
    return at - Date.now() <= 5_000 ? '轮询' : formatInterval(at)
  }

  function intervalHint(card: DueCard, grade: Grade) {
    const interval = intervalOf(card, grade)
    return interval === '轮询' ? '评分后会在本页队列中轮询重现' : `评分后 ${interval} 再次复习`
  }

  async function loadQueue() {
    loading.value = true
    reviewedCount.value = 0
    orphanCount.value = 0
    try {
      queue.value = await memoryCycleApi.due(50)
      await loadCurrentCardContent()
    } finally {
      loading.value = false
    }
  }

  async function loadCurrentCardContent() {
    while (currentCard.value) {
      const card = currentCard.value
      if (loadedContentIds.value.has(card.cardId)) return

      contentLoadingCardId.value = card.cardId
      contentError.value = ''
      try {
        const content = await loadCardContent([card.cardId])
        const details = content.get(card.cardId)
        if (details) {
          Object.assign(card, details)
          loadedContentIds.value = new Set(loadedContentIds.value).add(card.cardId)
          return
        }

        orphanCount.value++
        queue.value = queue.value.filter((item) => item.cardId !== card.cardId)
        void memoryCycleApi.forget(card.cardId).catch(() => {})
        emitPluginStateChange({ pluginId: PLUGIN_ID, cardIds: [card.cardId] })
      } catch (error) {
        contentError.value = error instanceof Error ? error.message : '读取卡片失败，请重试'
        return
      } finally {
        contentLoadingCardId.value = null
      }
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
      void loadHistories()
    } catch (error) {
      enabledCardsError.value = error instanceof Error ? error.message : '加载失败，请重试'
    } finally {
      enabledCardsLoading.value = false
    }
  }

  function selectTab(tab: ReviewTab) {
    activeTab.value = tab
    if (tab === 'due' || tab === 'enabled') {
      if (!enabledCardsLoaded.value) void loadEnabledCardList()
      else if (tab === 'due' && !historiesLoaded.value) void loadHistories()
    }
  }

  async function openPreview(card: EnabledMemoryCard) {
    previewCard.value = card
    previewTab.value = 'content'
    if (Object.prototype.hasOwnProperty.call(histories.value, card.cardId)) return
    try {
      histories.value = { ...histories.value, [card.cardId]: await memoryCycleApi.history(card.cardId) }
    } catch {
      // Preview remains usable without history.
    }
  }

  function closePreview() {
    previewCard.value = null
  }

  function requestDeleteCard(card: { cardId: string }) {
    if (busyIds.value.has(card.cardId)) return
    deleteTarget.value = card
    deleteError.value = ''
    deleteMessage.value = ''
  }

  async function locateCard(card: { cardId: string; noteId?: string }) {
    const tab = window.open('', '_blank')
    if (!tab) {
      toast('浏览器阻止了新标签页', 'error')
      return
    }
    tab.opener = null
    let noteId = card.noteId
    if (!noteId) {
      try {
        noteId = await resolveCardNoteId(card.cardId)
      } catch (error) {
        tab.close()
        toast(`解析卡片位置失败：${(error as Error).message}`, 'error')
        return
      }
    }
    if (!noteId) {
      tab.close()
      toast('卡片所属笔记尚未加载', 'error')
      return
    }
    const target: RouteLocationRaw = router.hasRoute('note')
      ? { name: 'note', params: { id: noteId }, query: { cardId: card.cardId, onlyNote: noteId } }
      : { path: `/notes/${noteId}`, query: { cardId: card.cardId, onlyNote: noteId } }
    const resolved = router.resolve(target)
    tab.location.href = new URL(resolved.href, window.location.href).href
  }

  function cancelDeleteCard() {
    if (!deleting.value) deleteTarget.value = null
  }

  function removeDeletedCard(cardId: string) {
    queue.value = queue.value.filter((card) => card.cardId !== cardId)
    enabledCards.value = enabledCards.value.filter((card) => card.cardId !== cardId)
    reviewedStates.delete(cardId)
    if (previewCard.value?.cardId === cardId) previewCard.value = null
    if (contentLoadingCardId.value === cardId) contentLoadingCardId.value = null
    const loaded = new Set(loadedContentIds.value)
    loaded.delete(cardId)
    loadedContentIds.value = loaded
    const nextHistories = { ...histories.value }
    delete nextHistories[cardId]
    histories.value = nextHistories
    contentError.value = ''
  }

  async function confirmDeleteCard() {
    const target = deleteTarget.value
    if (!target || deleting.value) return
    deleting.value = true
    deleteError.value = ''
    try {
      const deleted = await deleteReviewCard(target.cardId)
      removeDeletedCard(target.cardId)
      deleteTarget.value = null
      deleteMessage.value = deleted === 'note' ? '唯一卡片已删除，笔记已一并删除' : '卡片已删除'
      emitPluginStateChange({ pluginId: PLUGIN_ID, cardIds: [target.cardId] })
      if (activeTab.value === 'review') await loadCurrentCardContent()
    } catch (error) {
      deleteTarget.value = null
      deleteError.value = error instanceof Error ? error.message : '请稍后重试'
    } finally {
      deleting.value = false
    }
  }

  const { onPreviewWheel, onPreviewMouseDown, dispose: disposePreviewInteractions } = createPreviewImageInteractions({
    grabbingClass: 'rv-img-grabbing',
  })
  const pluginDisabled = computed(() => !isPluginActive(PLUGIN_ID))

  async function openPluginSettings() {
    await router.push({ path: '/settings', query: { tab: 'plugins' } })
  }

  function removeFromView(cardId: string) {
    queue.value = queue.value.filter((card) => card.cardId !== cardId)
    reviewedCount.value++
  }

  function requeueShort(card: DueCard & Partial<CardContent>, updated: MemoryCycleCardState) {
    const remaining = queue.value.filter((item) => item.cardId !== card.cardId)
    const remainingCount = remaining.length
    if (remainingCount === 0) {
      queue.value = [{
        ...card,
        state: updated.schedule.state,
        level: updated.schedule.level,
        dueAt: updated.schedule.dueAt,
        gradePreview: updated.gradePreview ?? card.gradePreview,
      }]
      return
    }
    const minPosition = Math.floor(remainingCount / 5) + 1
    const position = minPosition + Math.floor(Math.random() * (remainingCount - minPosition + 1))
    remaining.splice(position, 0, {
      ...card,
      state: updated.schedule.state,
      level: updated.schedule.level,
      dueAt: updated.schedule.dueAt,
      gradePreview: updated.gradePreview ?? card.gradePreview,
    })
    queue.value = remaining
  }

  function settleGrade(card: DueCard & Partial<CardContent>, gradeValue: Grade, updated: MemoryCycleCardState) {
    reviewedStates.set(card.cardId, updated)
    const enabledCard = enabledCards.value.find((item) => item.cardId === card.cardId)
    if (enabledCard) {
      enabledCard.schedule = updated.schedule
      enabledCards.value.sort((a, b) => a.schedule.dueAt - b.schedule.dueAt)
    }
    const entries = histories.value[card.cardId] ?? []
    histories.value = {
      ...histories.value,
      [card.cardId]: [...entries, {
        cardId: card.cardId,
        reviewedAt: Date.now(),
        grade: gradeValue,
        state: updated.schedule.state,
        level: updated.schedule.level,
        dueAt: updated.schedule.dueAt,
      }],
    }
    if (updated.schedule.state === 'learning' || updated.schedule.state === 'relearning') {
      requeueShort(card, updated)
    } else {
      removeFromView(card.cardId)
    }
  }

  async function grade(card: DueCard, gradeValue: Grade) {
    if (busyIds.value.has(card.cardId) || !loadedContentIds.value.has(card.cardId)) return
    busyIds.value.add(card.cardId)
    try {
      settleGrade(card, gradeValue, await memoryCycle.review(card.cardId, gradeValue))
      await loadCurrentCardContent()
    } finally {
      busyIds.value.delete(card.cardId)
    }
  }

  function onKeydown(e: KeyboardEvent) {
    const target = e.target as HTMLElement
    if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) return
    if (deleteTarget.value) return
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

  const GRADES: { grade: Grade; label: string }[] = [
    { grade: 1, label: '忘记' },
    { grade: 2, label: '困难' },
    { grade: 3, label: '良好' },
    { grade: 4, label: '简单' },
  ]

  onMounted(async () => {
    await refreshPluginAvailability()
    if (isPluginActive(PLUGIN_ID)) void loadQueue()
    else loading.value = false
    window.addEventListener('keydown', onKeydown)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown)
    disposePreviewInteractions()
  })

  return {
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
    deleteTarget,
    deleting,
    deleteMessage,
    deleteError,
    dueTodayCards,
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
  }
}
