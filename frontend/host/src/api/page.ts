import { http } from '@/core/http'
import type { NoteCard, CardView, NotePageData } from './types'

export interface CardPatch {
  id: string
  title?: string | null
  x?: number | null
  y?: number | null
  w?: number | null
  h?: number | null
  z?: number | null
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
    http.get<NotePageData>(
      `/api/notes/${noteId}/cards${ids && ids.length ? `?ids=${ids.map(encodeURIComponent).join(',')}` : ''}`,
    ),

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
  ) => http.post<CardView>(`/api/notes/${noteId}/cards`, payload),

  patchBlocks: (noteId: string, cards: CardPatch[]) =>
    http.post<{ ok: boolean }>(`/api/notes/${noteId}/cards/patch`, { cards }),

  patchBlock: (noteId: string, cardId: string, patch: CardPatch) =>
    http.post<NoteCard>(`/api/notes/${noteId}/cards/${cardId}/patch`, patch),

  /**
   * Write markdown typed inside a card back into the note.
   *
   * <p>`baseContentMd` 是编辑开始时卡片上的正文：磁盘上已经不是那一份时后端回 409，
   * 由调用方提示并重新载入 —— 不覆盖外部编辑器（Obsidian / VS Code）写进去的改动。
   */
  writeContent: (noteId: string, cardId: string, contentMd: string, baseContentMd: string) =>
    http.post<CardView>(`/api/notes/${noteId}/cards/${cardId}/content`, {
      contentMd,
      baseContentMd,
    }),

  duplicateBlock: (noteId: string, cardId: string, dx = 48, dy = 48) =>
    http.post<NoteCard>(`/api/notes/${noteId}/cards/${cardId}/duplicate`, undefined, {
      dx,
      dy,
    }),

  removeBlock: (noteId: string, cardId: string, deleteContent = false) =>
    http.post<{ ok: boolean }>(`/api/notes/${noteId}/cards/${cardId}/delete`, undefined, { deleteContent }),

  zOrder: (noteId: string, id: string, op: 'top' | 'bottom' | 'up' | 'down') =>
    http.post<NoteCard>(`/api/notes/${noteId}/cards/z`, { id, op }),
}
