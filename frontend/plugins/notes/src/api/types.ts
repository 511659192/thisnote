/**
 * JSON contract for backend API and domain responses.
 * Keep both sides in sync when the API changes.
 */

export interface Book {
  id: string
  type: string
  title: string
  icon?: string | null
  sortOrder: number
  createdAt: number
  updatedAt: number
}

/** Card metadata returned by the backend inside the Card domain object. */
export interface CardMeta {
  cardId: string
  sourceFileName?: string | null
  noteId?: string | null
  x: number
  y: number
  w: number
  h: number
  /** Legacy persisted field; runtime stacking uses geometry plus recent interaction, without persistence. */
  z: number
  color?: string | null
  status?: string | null
  collapsed: boolean
  ext?: Record<string, unknown> | null
}

/** Card domain object returned by the backend. */
export interface Card {
  meta: CardMeta
  content: string
}

/** Frontend presentation shape derived from `Card`; not part of the HTTP contract. */
export interface CardView {
  card: CardMeta
  contentMd: string
  /** Client-only transient flags. */
  selected?: boolean
}

/**
 * 一篇笔记的 meta（后端原样返回的列表行）：没有正文体，摘要与计数是后端写入时算好的缓存。
 */
export interface NoteDto {
  noteId: string
  title: string
  /** 归属分组 id（单归属，直接取自笔记 meta）；显示用的标题由界面侧台账解析。 */
  bookId?: string | null
  /** 卡片 ID 清单（顺序即阅读顺序）。 */
  cardIds: string[]
}

