import { http } from '@/core/http'
import type { CardMeta, CardView, NotePageData } from './types'

export interface CardUpdate {
  id: string
  title?: string | null
  x?: number | null
  y?: number | null
  w?: number | null
  h?: number | null
  color?: string | null
  status?: string | null
  collapsed?: boolean | null
}

/**
 * A note's cards: geometry, text, and the whole-card operations.
 *
 * <p>Nested under the note because a card has no meaning outside its note — there is no
 * board/container entity in this model.
 *
 * <p>列表接口只给卡片清单（`NoteMeta.cards`，只有身份）；坐标与正文都在卡片文件里，
 * 属于页面级数据，打开笔记时用 {@link pageApi.get} 的 `ids` 按视口分批取。
 */
export const pageApi = {
  /** 这几张卡（`ids` 省略 = 整页）：卡片几何 + 它自己的正文。 */
  get: (noteId: string, ids?: string[]) =>
    http.get<NotePageData>('/api/notes/cards', {
      noteId,
      ids: ids && ids.length ? ids.map(encodeURIComponent).join(',') : undefined,
    }),

  addBlock: (
    noteId: string,
    payload: {
      x?: number
      y?: number
      w?: number
      h?: number
      contentMd?: string
      title?: string
      color?: string
    },
  ) => http.post<CardView>('/api/notes/cards/create', payload, { noteId }),

  updateBlocks: (cards: CardUpdate[]) =>
    http.post<{ ok: boolean }>('/api/notes/cards/batch-update', { cards }),

  updateBlock: (cardId: string, changes: CardUpdate) =>
    http.post<CardMeta>('/api/notes/cards/update', changes, { cardId }),

  /**
   * Write markdown typed inside a card back into the note.
   *
   * <p>`baseContentMd` 是编辑开始时卡片上的正文：磁盘上已经不是那一份时后端回 409，
   * 由调用方提示并重新载入 —— 不覆盖外部编辑器（Obsidian / VS Code）写进去的改动。
   */
  writeContent: (cardId: string, contentMd: string, baseContentMd: string) =>
    http.post<CardView>('/api/notes/cards/content', { contentMd, baseContentMd }, { cardId }),

}
