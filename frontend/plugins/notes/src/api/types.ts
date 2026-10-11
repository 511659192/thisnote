/**
 * JSON contract mirroring the backend DTOs (backend/.../api/dto/Dtos.java).
 * Keep both sides in sync when the API changes.
 */

export interface Notebook {
  id: string
  type: string
  title: string
  parentId?: string | null
  icon?: string | null
  sortOrder?: number | null
  count?: number | null
  createdAt?: number
  updatedAt?: number
}

export type NoteType = 'note' | 'daily' | 'template'

/** Card metadata returned directly by the backend. The card body is carried by `CardView`. */
export interface CardMeta {
  v: number
  id: string
  title: string
  x: number
  y: number
  w: number
  h: number
  z: number
  color?: string | null
  status?: string | null
  collapsed: boolean
  ext?: Record<string, unknown> | null
  createdAt: number
  updatedAt: number
}

/** A card's metadata plus the note-level context delivered by the page endpoint. */
export interface CardView {
  card: CardMeta
  /** Card body, stored separately from the card's metadata. */
  contentMd: string
  noteTitle: string
  /** Client-only transient flags. */
  selected?: boolean
}

export interface NotePageData {
  noteId: string
  noteTitle: string
  cards: CardView[]
  maxCardsPerNote: number
  updatedAt: number
}

export type CardId = string

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

export interface NoteDetail {
  id: string
  title: string
  type: NoteType
  /** 有序卡片 ID 清单；页面级数据按需取。 */
  cards: CardId[]
  notebooks: Notebook[]
  createdAt: number
  updatedAt: number
}


