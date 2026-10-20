import { http } from '@thisnote/plugin-api'

/** 记忆周期插件自己的 API 与类型 —— 属于插件，不属于通用底座。 */

export interface ScheduleView {
  cardId: string | null
  state: 'new' | 'learning' | 'review' | 'relearning'
  level: number
  dueAt: number
  lastReviewedAt: number | null
  reps: number
  lapses: number
  due: boolean
}

export interface MemoryCycleCardState {
  cardId: string
  enabled: boolean
  schedule: ScheduleView
  /** 按评分 1..4 预览的下一次复习时间（epoch 毫秒），与真实调度共用同一套计算 */
  gradePreview?: Record<string, number>
}

/** 一次复习记录（插件侧的 .schedule-history.json）。 */
export interface ScheduleHistoryEntry {
  cardId: string
  reviewedAt: number
  grade: number
  state: string
  level: number
  dueAt: number
}

/** 复习评分：与 Anki 一致的四档 */
export type Grade = 1 | 2 | 3 | 4

/**
 * 复习页的到期卡片：**只有排期**（外加各评分的间隔预览）。
 *
 * <p>正文与标题不在响应里 —— 复习插件不读笔记数据，因此不依赖笔记插件；渲染需要的内容由
 * {@link loadCardContent} 用核心的公开 API 现拼。
 */
export interface DueCard {
  cardId: string
  dueAt: number
  state: string
  level: number
  gradePreview: Record<string, number>
}

/** 一张卡片的渲染内容（由界面侧自己从核心 API 拼出来） */
export interface CardContent {
  noteId: string
  noteTitle: string
  contentMd: string
}

export interface EnabledMemoryCard extends CardContent {
  cardId: string
  schedule: ScheduleView
}

type NoteCardIndex = { noteId: string; title: string; cardIds: string[] }

let noteCardIndexCache: { notes: NoteCardIndex[]; loadedAt: number } | null = null
let noteCardIndexRequest: Promise<NoteCardIndex[]> | null = null
let noteCardIndexVersion = 0

async function loadNoteCardIndex(): Promise<NoteCardIndex[]> {
  if (noteCardIndexCache && Date.now() - noteCardIndexCache.loadedAt <= 60_000) {
    return noteCardIndexCache.notes
  }
  const version = noteCardIndexVersion
  noteCardIndexRequest ??= http.get<NoteCardIndex[]>('/api/notes/get', { limit: 2000 })
    .then((notes) => {
      if (version === noteCardIndexVersion) {
        noteCardIndexCache = { notes, loadedAt: Date.now() }
      }
      return notes
    })
    .finally(() => {
      if (version === noteCardIndexVersion) noteCardIndexRequest = null
    })
  return noteCardIndexRequest!
}

function invalidateNoteCardIndex() {
  noteCardIndexVersion++
  noteCardIndexCache = null
  noteCardIndexRequest = null
}

export const memoryCycleApi = {
  cards: (ids: string[]) =>
    http.get<MemoryCycleCardState[]>(
      '/api/plugins/memory-cycle/get-cards', { ids: ids.join(',') },
    ),
  setEnabled: (cardId: string, value: boolean) =>
    http.post<{ enabled: boolean }>(
      `/api/plugins/memory-cycle/update-state`, {}, { cardId, value },
    ),
  /** 批量开启/关闭（按卡片）。 */
  setStates: (cardIds: string[], value: boolean) =>
    http.post<{ updated: number }>(
      '/api/plugins/memory-cycle/update-state-batch', { cardIds, value },
    ),
  review: (cardId: string, grade: Grade) =>
    http.post<MemoryCycleCardState>(
      `/api/plugins/memory-cycle/review`, { grade }, { cardId },
    ),
  due: (limit = 50) =>
    http.get<DueCard[]>(
      '/api/plugins/memory-cycle/get-due', { limit },
    ),
  /** 今日到期（自然天）的卡片数量。 */
  dueCount: () =>
    http.get<{ count: number }>('/api/plugins/memory-cycle/get-due-count'),
  /** 某张卡片的复习历史（从旧到新）。 */
  history: (cardId: string) =>
    http.get<ScheduleHistoryEntry[]>(
      '/api/plugins/memory-cycle/get-history', { cardId },
    ),
  /** 批量取复习历史（卡片 id → 记录）。 */
  histories: (cardIds: string[]) =>
    http.get<Record<string, ScheduleHistoryEntry[]>>(
      '/api/plugins/memory-cycle/get-histories', { ids: cardIds.join(',') },
    ),
  /** 删掉一条排期（复习页发现它是孤儿时调用）。 */
  forget: (cardId: string) =>
    http.post<{ cardId: string; removed: boolean }>(
      `/api/plugins/memory-cycle/delete-schedule`, undefined, { cardId },
    ),
}

async function findFreshCardOwner(cardId: string): Promise<NoteCardIndex | null> {
  const notes = await http.get<NoteCardIndex[]>('/api/notes/get', { limit: 2000 })
  return notes.find((note) => (note.cardIds ?? []).includes(cardId)) ?? null
}

export async function resolveCardNoteId(cardId: string): Promise<string> {
  const owner = await findFreshCardOwner(cardId)
  return owner?.noteId ?? ''
}

/** 删除卡片；若它是笔记唯一卡片，则删除整篇笔记。 */
export async function deleteReviewCard(cardId: string): Promise<'card' | 'note'> {
  const owner = await findFreshCardOwner(cardId)
  invalidateNoteCardIndex()
  if (owner && (owner.cardIds?.length ?? 0) <= 1) {
    await http.post<{ ok: boolean }>('/api/notes/delete', undefined, { id: owner.noteId })
    void memoryCycleApi.forget(cardId).catch(() => undefined)
    return 'note'
  }
  if (owner) {
    await http.post<void>('/api/notes/cards/delete', undefined, { noteId: owner.noteId, cardId })
  }
  await memoryCycleApi.forget(cardId).catch(() => undefined)
  return 'card'
}

/** 按笔记元数据中的 cardIds 定位目标卡片，只读取目标笔记的卡片明细。 */
export async function loadCardContent(cardIds: string[]): Promise<Map<string, CardContent>> {
  const want = new Set(cardIds)
  const found = new Map<string, CardContent>()
  if (!want.size) return found
  const indexWasFresh = noteCardIndexCache !== null
    && Date.now() - noteCardIndexCache.loadedAt <= 60_000
  let notes = await loadNoteCardIndex()
  const findOwners = (items: NoteCardIndex[]) => items
    .map((note) => ({
      note,
      cardIds: (note.cardIds ?? []).filter((cardId) => want.has(cardId)),
    }))
    .filter(({ cardIds: noteCardIds }) => noteCardIds.length > 0)
  let owners = findOwners(notes)
  if (!owners.length && indexWasFresh) {
    invalidateNoteCardIndex()
    notes = await loadNoteCardIndex()
    owners = findOwners(notes)
  }
  const pages = await Promise.all(owners.map(async ({ note, cardIds: noteCardIds }) => {
    // /api/notes/cards/get 返回的是卡片数组本身，没有 { cards } 包装
    const cards = await http.get<Array<{ meta: { cardId: string }; content: string }>>(
      '/api/notes/cards/get',
      { noteId: note.noteId, cardIds: noteCardIds.join(',') },
    )
    return { note, cards }
  })).catch((error) => {
    invalidateNoteCardIndex()
    throw error
  })
  for (const item of pages) {
    for (const card of item.cards) {
      if (want.has(card.meta.cardId)) {
        found.set(card.meta.cardId, {
          noteId: item.note.noteId,
          noteTitle: item.note.title,
          contentMd: card.content,
        })
      }
    }
  }
  return found
}

export async function loadEnabledCards(): Promise<EnabledMemoryCard[]> {
  const enabledCards: EnabledMemoryCard[] = []
  const contentById = new Map<string, CardContent>()
  const notePageSize = 500
  const noteBatchSize = 8
  const stateBatchSize = 100
  let offset = 0

  const collectEnabled = async (cardIds: string[]) => {
    const states = await memoryCycleApi.cards(cardIds)
    for (const state of states) {
      const content = contentById.get(state.cardId)
      if (state.enabled && content) {
        enabledCards.push({ cardId: state.cardId, ...content, schedule: state.schedule })
      }
    }
    for (const cardId of cardIds) contentById.delete(cardId)
  }

  while (true) {
    const notes = await http.get<Array<{ noteId: string; title: string }>>('/api/notes/get', {
      limit: notePageSize,
      offset,
    })
    if (!notes.length) break

    for (let i = 0; i < notes.length; i += noteBatchSize) {
      const batch = notes.slice(i, i + noteBatchSize)
      const pages = await Promise.all(batch.map(async (note) => ({
        note,
        // /api/notes/cards/get 返回裸数组
        cards: await http.get<Array<{ meta: { cardId: string }; content: string }>>(
          '/api/notes/cards/get',
          { noteId: note.noteId },
        ),
      })))
      for (const { note, cards } of pages) {
        for (const card of cards) {
          const cardId = card.meta.cardId
          if (!contentById.has(cardId)) {
            contentById.set(cardId, {
              noteId: note.noteId,
              noteTitle: note.title,
              contentMd: card.content,
            })
          }
        }
      }

      while (contentById.size >= stateBatchSize) {
        await collectEnabled([...contentById.keys()].slice(0, stateBatchSize))
      }
    }

    offset += notes.length
    if (notes.length < notePageSize) break
  }

  if (contentById.size) await collectEnabled([...contentById.keys()])
  return enabledCards.sort((a, b) => a.schedule.dueAt - b.schedule.dueAt)
}

/** 间隔人话文案：毫秒差 → 「N 分钟 / N 小时 / N 天」。 */
export function formatInterval(dueAtMs: number): string {
  const diff = dueAtMs - Date.now()
  if (diff < 90_000) return '1 分钟'
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)} 分钟`
  if (diff < 86_400_000) return `${Math.round(diff / 3_600_000)} 小时`
  return `${Math.round(diff / 86_400_000)} 天`
}
