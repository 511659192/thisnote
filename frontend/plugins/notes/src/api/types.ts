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

/**
 * A card on a note's page.
 *
 * `contentMd` is this card's own fragment of the note; the note's markdown is the
 * concatenation of its cards in order, so a card's text never shifts when a neighbouring card
 * grows.
 *
 * `title` is an optional label the *user* gives the card. It is not note content, and when it
 * is empty the card shows no title bar at all.
 */
export interface NoteCard {
  id: string
  noteId: string
  title: string
  x: number
  y: number
  w: number
  h: number
  z: number
  color?: string | null
  status?: string | null
  collapsed: boolean
  contentMd: string
  createdAt: number
  updatedAt: number
}

/**
 * A card plus the note-level context it renders with, as delivered by the page endpoint.
 * Kept separate from {@link NoteCard} so the card's own text lives in exactly one place.
 */
export interface CardView {
  card: NoteCard
  /** Mirror of `card.contentMd`, kept for convenience when rendering. */
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
  /** 本篇卡片文件相对笔记根目录的路径清单（顺序即阅读顺序）。 */
  cardPath: string[]
  createdAt: number
  updatedAt: number
}

export interface NoteDetail {
  id: string
  title: string
  contentMd: string
  type: NoteType
  /** 有序卡片 ID 清单；页面级数据按需取。 */
  cards: CardId[]
  notebooks: Notebook[]
  createdAt: number
  updatedAt: number
}


