/**
 * JSON contract mirroring the backend DTOs (backend/.../api/dto/Dtos.java).
 * Keep both sides in sync when the API changes.
 */

export interface Tag {
  id: string
  name: string
  color?: string | null
  count?: number | null
}

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
  tags: Tag[]
  /** Client-only transient flags. */
  selected?: boolean
}

export interface NotePageData {
  noteId: string
  noteTitle: string
  cards: CardView[]
  tags: Tag[]
  maxCardsPerNote: number
  updatedAt: number
}

export interface Card {
  /** 卡片 id：稳定身份（文件名变了也不变），按需取内容时用它。 */
  id: string
  /** 卡片文件相对保险库根目录的路径；顺序即阅读顺序。 */
  file: string
}

/**
 * 一篇笔记的 meta（后端原样返回的列表行）：没有正文体，摘要与计数是后端写入时算好的缓存。
 */
export interface NoteMeta {
  v: number
  id: string
  title: string
  type: NoteType
  /** 标签名。颜色与计数只在独立的 `GET /api/tags` 目录里，列表接口不背。 */
  tags: string[]
  /** 归属分组 id（单归属，直接取自笔记 meta）；显示用的标题由界面侧台账解析。 */
  notebookId?: string | null
  /** 本篇的卡片清单（顺序即阅读顺序）。 */
  cards: Card[]
  /** 列表页摘要：正文的纯文本前缀。 */
  summary: string
  /** 出链清单（写时算好）：raw 原文 + label 显示文字 + to 解析到的笔记 id。 */
  outLinks: Link[]
  /** 入链清单（对端写入时落过来）：from 是来源笔记。 */
  inLinks: Link[]
  outLinksCount: number
  inLinksCount: number
  unresolvedLinksCount: number
  createdAt: number
  updatedAt: number
}

/**
 * 一条链接（出链/入链共用同一个形状），方向由 `from → to` 表达。
 *
 * `to` 为空表示正文里写了 `[[...]]` 但目标还没建（此时靠 `raw` 显示原文并"点击新建"）；
 * `raw` 只有出链有。
 */
export interface Link {
  from?: string | null
  to?: string | null
  label?: string | null
  raw?: string | null
}

export interface NoteDetail {
  id: string
  title: string
  contentMd: string
  type: NoteType
  tags: string[]
  /** 卡片清单（只有身份）：页面级数据按需取，见 {@link Card}。 */
  cards: Card[]
  notebooks: Notebook[]
  outbound: Link[]
  backlinks: Link[]
  unresolved: string[]
  unlinkedMentions: string[]
  createdAt: number
  updatedAt: number
}

export interface OpEntry {
  id: string
  entityType: string
  entityId?: string | null
  action: string
  payload?: string | null
  createdAt: number
}

