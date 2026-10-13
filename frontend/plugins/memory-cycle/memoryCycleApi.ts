import { http } from '@thisnode/plugin-api'

/** 记忆周期插件自己的 API 与类型 —— 属于插件，不属于通用底座。 */

export interface ScheduleView {
  cardId: string | null
  state: 'new' | 'learning' | 'review' | 'relearning'
  ease: number
  intervalDays: number
  dueAt: number
  lastReviewedAt: number | null
  reps: number
  lapses: number
  due: boolean
}

export interface MemoryCycleCardState {
  cardId: string
  enabled: boolean
  learningStep: number
  schedule: ScheduleView
  /** 按评分 1..4 预览的下一次复习时间（epoch 毫秒），与真实调度共用同一套计算 */
  gradePreview?: Record<string, number>
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
  learningStep: number
  gradePreview: Record<string, number>
}

/** 一张卡片的渲染内容（由界面侧自己从核心 API 拼出来） */
export interface CardContent {
  noteTitle: string
  contentMd: string
}

export interface EnabledMemoryCard extends CardContent {
  cardId: string
  schedule: ScheduleView
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
  review: (cardId: string, grade: Grade) =>
    http.post<MemoryCycleCardState>(
      `/api/plugins/memory-cycle/review`, { grade }, { cardId },
    ),
  due: (limit = 50) =>
    http.get<DueCard[]>(
      '/api/plugins/memory-cycle/get-due', { limit },
    ),
  /** 删掉一条排期（复习页发现它是孤儿时调用）。 */
  forget: (cardId: string) =>
    http.post<{ cardId: string; removed: boolean }>(
      `/api/plugins/memory-cycle/delete-schedule`, undefined, { cardId },
    ),
}

/**
 * 按 cardId 把卡片内容拼出来：列出笔记 → 并发取页面 → 找全就停。
 *
 * <p>这是「复习插件不依赖笔记插件」的代价所在：首屏最多 N 个请求（N = 笔记数）。
 * 换来的是两个插件之间没有任何契约 —— 笔记插件缺位时复习插件照样能起来（只是拼不出内容）。
 */
export async function loadCardContent(cardIds: string[]): Promise<Map<string, CardContent>> {
  const want = new Set(cardIds)
  const found = new Map<string, CardContent>()
  if (!want.size) return found
  const notes = await http.get<Array<{ noteId: string; title: string }>>('/api/notes/get', { limit: 2000 })
  const batchSize = 8
  for (let i = 0; i < notes.length && found.size < want.size; i += batchSize) {
    const batch = notes.slice(i, i + batchSize)
    const pages = await Promise.all(batch.map(async (note) => {
      try {
        const page = await http.get<{
          cards: Array<{ meta: { cardId: string }; content: string }>
        }>('/api/notes/cards/get', { noteId: note.noteId })
        return { note, page }
      } catch {
        return null
      }
    }))
    for (const item of pages) {
      if (!item) continue
      for (const card of item.page.cards) {
        const cardId = card.meta.cardId
        if (want.has(cardId) && !found.has(cardId)) {
          found.set(cardId, {
            noteTitle: item.note.title,
            contentMd: card.content,
          })
        }
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
        page: await http.get<{
          cards: Array<{ meta: { cardId: string }; content: string }>
        }>('/api/notes/cards/get', { noteId: note.noteId }),
      })))
      for (const { note, page } of pages) {
        for (const card of page.cards) {
          const cardId = card.meta.cardId
          if (!contentById.has(cardId)) {
            contentById.set(cardId, { noteTitle: note.title, contentMd: card.content })
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
