import { http } from '@thisnode/plugin-api'
import type { Card } from './types'

export interface CardUpdate {
  cardId: string
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
 * <p>笔记 meta 保留稳定 cardId 清单；文件名由后端索引解析，前端按视口分批取内容。
 */
export const pageApi = {
  /** cardIds 省略 = 整页：卡片几何 + 它自己的正文。 */
  get: (noteId: string, cardIds?: string[]) =>
    http.get<Card[]>('/api/notes/cards/get', {
      noteId,
      cardIds: cardIds && cardIds.length ? cardIds.join(',') : undefined,
    }),

  addBlock: (
    noteId: string,
    payload: {
      x?: number
      y?: number
      w?: number
      h?: number
      content?: string
      color?: string
    },
  ) => http.post<Card>('/api/notes/cards/create', { ...payload, noteId }),

  updateBlocks: (noteId: string, cards: CardUpdate[]) =>
    http.post<{ ok: boolean }>('/api/notes/cards/batch-update', { noteId, cards }),

  /**
   * Write markdown typed inside a card back into the note.
   *
   * <p>`baseContentMd` 是编辑开始时卡片上的正文：磁盘上已经不是那一份时后端回 409，
   * 由调用方提示并重新载入 —— 不覆盖外部编辑器（Obsidian / VS Code）写进去的改动。
   */
  writeContent: (noteId: string, cardId: string, contentMd: string, baseContentMd: string) =>
    http.post<Card>('/api/notes/cards/update-content', { noteId, cardId, contentMd, baseContentMd }),

  /** Permanently delete the identified card and remove it from its note's manifest. */
  deleteBlock: (noteId: string, cardId: string) =>
    http.post<void>('/api/notes/cards/delete', undefined, { noteId, cardId }),
}
