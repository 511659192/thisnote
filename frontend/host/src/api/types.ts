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
export interface NoteBlock {
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
 * A block plus the note-level context it renders with, as delivered by the page endpoint.
 * Kept separate from {@link NoteBlock} so the card's own text lives in exactly one place.
 */
export interface NoteBlockView {
  block: NoteBlock
  /** Mirror of `block.contentMd`, kept for convenience when rendering. */
  contentMd: string
  noteTitle: string
  tags: Tag[]
  /** Client-only transient flags. */
  selected?: boolean
}

export interface NotePageData {
  noteId: string
  noteTitle: string
  blocks: NoteBlockView[]
  tags: Tag[]
  maxBlocksPerNote: number
  updatedAt: number
}

export interface CardRef {
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
  dailyDate?: string | null
  source?: string | null
  status?: string | null
  archived: boolean
  deleted: boolean
  deletedAt?: number | null
  /** 标签名。颜色与计数只在独立的 `GET /api/tags` 目录里，列表接口不背。 */
  tags: string[]
  aliases: string[]
  /** 归属分组 id（单归属，直接取自笔记 meta）；显示用的标题由前端台账解析。 */
  notebookId?: string | null
  /** 本篇的卡片清单（顺序即阅读顺序）。 */
  cards: CardRef[]
  /** 列表页摘要：正文的纯文本前缀。 */
  summary: string
  /** 出链清单（写时算好）：target 原文 + label 显示文字 + to 解析到的笔记 id。 */
  links: LinkRef[]
  outboundCount: number
  inboundCount: number
  unresolvedCount: number
  createdAt: number
  updatedAt: number
}

export interface LinkRef {
  target: string
  label?: string | null
  to?: string | null
}

export interface Backlink {
  noteId: string
  title: string
  label?: string | null
  heading?: string | null
  context?: string
  updatedAt: number
}

export interface Link {
  id: string
  fromNodeId: string
  toNodeId?: string | null
  toTitle?: string | null
  label?: string | null
  type: string
  resolved: boolean
}

export interface NoteDetail {
  id: string
  title: string
  contentMd: string
  type: NoteType
  source?: string | null
  status?: string | null
  archived: boolean
  deleted: boolean
  dailyDate?: string | null
  /** 卡片清单（只有身份）：页面级数据按需取，见 {@link CardRef}。 */
  cards: CardRef[]
  tags: string[]
  aliases: string[]
  notebooks: Notebook[]
  outbound: Link[]
  backlinks: Backlink[]
  unresolved: string[]
  unlinkedMentions: string[]
  createdAt: number
  updatedAt: number
}

export interface Snapshot {
  id: string
  kind: string
  path: string
  size: number
  nodeCount: number
  createdAt: number
}

export interface SystemInfo {
  version: string
  dbPath: string
  markdownRoot: string
  attachmentRoot: string
  snapshotRoot: string
  nodeCount: number
  uptimeMs: number
}

export interface OpEntry {
  id: string
  entityType: string
  entityId?: string | null
  action: string
  payload?: string | null
  createdAt: number
}

/** 笔记目录的同步事件（外部改动被发现时产生），见后端的 NoteSyncService。 */
export interface SyncEvent {
  kind: string
  title: string
  message: string
  at: number
}

/** List filter used by the sidebar (F-N-03). */
export type ListFilter =
  | 'all'
  | 'recent'
  | 'daily'
  | 'template'
  | 'archived'
  | 'trash'
