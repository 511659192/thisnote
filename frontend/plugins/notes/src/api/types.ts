/**
 * JSON contract for backend API and domain responses.
 * Keep both sides in sync when the API changes.
 */

export interface Notebook {
  v: number
  id: string
  type: string
  title: string
  icon?: string | null
  sortOrder: number
  createdAt: number
  updatedAt: number
}

export type NoteType = 'note' | 'daily' | 'template'

/** Card metadata returned by the backend inside the Card domain object. */
export interface CardMeta {
  v: number
  id: string
  title: string
  x: number
  y: number
  w: number
  h: number
  /** Legacy persisted field; visual stacking is derived from geometry. */
  z: number
  color?: string | null
  status?: string | null
  collapsed: boolean
  ext?: Record<string, unknown> | null
  createdAt: number
  updatedAt: number
}

/** Card domain object returned by the backend. */
export interface Card {
  meta: CardMeta
  content: string
  fileName: string
}

/** Frontend presentation shape derived from `Card`; not part of the HTTP contract. */
export interface CardView {
  fileName: string
  card: CardMeta
  contentMd: string
  /** Client-only transient flags. */
  selected?: boolean
}

export interface NoteDto {
  noteMeta: NoteMeta
  cards: Card[]
}

/**
 * 一篇笔记的 meta（后端原样返回的列表行）：没有正文体，摘要与计数是后端写入时算好的缓存。
 */
export interface NoteMeta {
  v: number
  id: string
  title: string
  type: NoteType
  /** 归属分组 id（单归属，直接取自笔记 meta）；显示用的标题由界面侧台账解析。 */
  notebookId?: string | null
  /** 本篇卡片文件名清单（顺序即阅读顺序）。 */
  cardFileNames: string[]
  createdAt: number
  updatedAt: number
}

