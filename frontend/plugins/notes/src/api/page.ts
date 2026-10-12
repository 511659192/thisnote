import { http } from '@thisnode/plugin-api'
import type { Card, CardMeta, NoteDto } from './types'

export interface CardUpdate {
  fileName: string
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
 * <p>笔记详情只给卡片文件名清单；坐标与正文都在卡片文件里，属于页面级数据，
 * 打开笔记时用 {@link pageApi.get} 的 `cardFileNames` 按视口分批取。
 */
export const pageApi = {
  /** 这些卡片文件名省略 = 整页：卡片几何 + 它自己的正文。 */
  get: (noteId: string, cardFileNames?: string[]) =>
    http.get<NoteDto>('/api/notes/cards', {
      noteId,
      cardFileNames: cardFileNames && cardFileNames.length ? cardFileNames.join(',') : undefined,
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
  ) => http.post<Card>('/api/notes/cards/create', payload, { noteId }),

  updateBlocks: (noteId: string, cards: CardUpdate[]) =>
    http.post<{ ok: boolean }>('/api/notes/cards/batch-update', { noteId, cards }),

  updateBlock: (noteId: string, fileName: string, changes: CardUpdate) =>
    http.post<CardMeta>('/api/notes/cards/update', { ...changes, noteId, fileName }),

  /**
   * Write markdown typed inside a card back into the note.
   *
   * <p>`baseContentMd` 是编辑开始时卡片上的正文：磁盘上已经不是那一份时后端回 409，
   * 由调用方提示并重新载入 —— 不覆盖外部编辑器（Obsidian / VS Code）写进去的改动。
   */
  writeContent: (noteId: string, cardFileName: string, contentMd: string, baseContentMd: string) =>
    http.post<Card>('/api/notes/cards/content', { noteId, cardFileName, contentMd, baseContentMd }),

  /** Permanently delete a card and remove it from its note's manifest. */
  deleteBlock: (noteId: string, fileName: string) =>
    http.post<void>('/api/notes/cards/delete', undefined, { noteId, fileName }),
}
