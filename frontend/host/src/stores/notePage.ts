import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { pageApi, type BlockPatch } from '@/api/page'
import { ApiError } from '@/core/http'
import type { NoteBlock, NoteBlockView, NotePageData } from '@/api/types'
import { useUiStore } from './ui'

export interface Viewport {
  x: number
  y: number
  zoom: number
}

export const MIN_ZOOM = 0.25
export const MAX_ZOOM = 3
export const BLOCK_MIN_W = 240
export const BLOCK_MIN_H = 88
/** Grid the page snaps to when snapping is on. */
export const GRID = 24

/**
 * Card geometry bounds.
 *
 * Cards are wide enough to write in, but never so wide that the page runs out of blank space
 * to click. The default column is ~62% of the visible page: comfortable for writing, and it
 * always leaves a clear strip of empty page to aim at.
 */
const LIMITS = { maxW: 680, defaultH: 260, widthRatio: 0.62, minVisibleGap: 140 }

/**
 * A note's page: the cards it is made of.
 *
 * <p>Model: a note *is* the page. Blocks belong to exactly one note and hold fragments of its
 * markdown, so there is one copy of the content — editing a card edits the note. There is no
 * board/container store any more.
 *
 * <p>Geometry writes are optimistic (the card follows the pointer) and coalesced into a single
 * batched PATCH when the gesture ends.
 */
export const usePageStore = defineStore('notePage', () => {
  const ui = useUiStore()

  const noteId = ref<string | null>(null)
  const noteTitle = ref('')
  const blocks = ref<NoteBlockView[]>([])
  const tags = ref<NotePageData['tags']>([])
  const maxBlocks = ref(100)
  const loading = ref(false)
  const savingContent = ref<string | null>(null)
  const editingBlockId = ref<string | null>(null)
  const selection = ref<string[]>([])
  const viewport = ref<Viewport>({ x: 0, y: 0, zoom: 1 })
  const gridVisible = ref(localStorage.getItem('thisnote.page.grid') !== 'off')
  const snapToGrid = ref(localStorage.getItem('thisnote.page.snap') === 'on')

  /** 卡片总数（含尚未取回正文的那些）：容量校验与「还可以加几张」都按它算。 */
  const count = computed(() => (totalCards.value > 0 ? totalCards.value : blocks.value.length))
  const full = computed(() => count.value >= maxBlocks.value)
  const remaining = computed(() => Math.max(0, maxBlocks.value - count.value))
  const limitLabel = computed(() => `${count.value} / ${maxBlocks.value} 张卡片`)
  const selectedBlocks = computed(() =>
    blocks.value.filter((b) => selection.value.includes(b.block.id)),
  )
  const singleSelected = computed(() =>
    selectedBlocks.value.length === 1 ? selectedBlocks.value[0] : null,
  )

  // ---------------------------------------------------------------- loading

  /**
   * 一批取多少张卡。
   *
   * <p>坐标与正文都在卡片文件里，属于页面级数据：打开笔记时先取这一批交给首屏，滚动到
   * 底部再由 {@link loadMore} 接着取。列表接口只给了卡片清单（id），不背几何。
   */
  const PAGE_SIZE = 8

  const pendingIds = ref<string[]>([])
  const totalCards = ref(0)

  /** 本笔记一共有多少张卡（含尚未取回正文的那部分）。 */
  const pendingCount = computed(() => pendingIds.value.length)

  /** 下一页的哨兵落在已加载卡片的下方：靠近视口时取下一批（见 NotePage 的观察器）。 */
  const sentinelY = computed(() => {
    let lowest = 0
    for (const b of blocks.value) lowest = Math.max(lowest, b.block.y + b.block.h)
    return lowest + 160
  })

  async function load(id: string, cardIds?: string[]) {
    loading.value = true
    try {
      blocks.value = []
      const all = cardIds ?? []
      totalCards.value = all.length
      const first = all.slice(0, PAGE_SIZE)
      pendingIds.value = all.slice(PAGE_SIZE)
      const page = await pageApi.get(id, first.length ? first : undefined)
      noteId.value = page.noteId
      noteTitle.value = page.noteTitle
      blocks.value = page.blocks
      tags.value = page.tags
      maxBlocks.value = page.maxBlocksPerNote || 100
      // 清单里可能含已被别处删掉的卡片：只惦记真正没取回来的那些。
      const loaded = new Set(blocks.value.map((b) => b.block.id))
      pendingIds.value = pendingIds.value.filter((x) => !loaded.has(x))
      selection.value = []
      editingBlockId.value = null
      return page
    } catch (e) {
      ui.toast((e as Error).message, 'error')
      return null
    } finally {
      loading.value = false
    }
  }

  /** 取下一批卡片（视口滚到底时调用）：几何与正文一起回来，追加到页面末尾。 */
  async function loadMore(): Promise<boolean> {
    const id = noteId.value
    if (!id || !pendingIds.value.length || loading.value) return false
    const batch = pendingIds.value.slice(0, PAGE_SIZE)
    try {
      const page = await pageApi.get(id, batch)
      const seen = new Set(blocks.value.map((b) => b.block.id))
      for (const view of page.blocks) {
        if (!seen.has(view.block.id)) blocks.value.push(view)
      }
      const arrived = new Set(page.blocks.map((b) => b.block.id))
      // 别处删掉的卡片不会再回来：把这一批里没回来的从待取队列里摘掉，避免反复请求。
      pendingIds.value = pendingIds.value
        .slice(PAGE_SIZE)
        .filter((x) => !arrived.has(x))
      return true
    } catch (e) {
      ui.toast((e as Error).message, 'error')
      return false
    }
  }

  /** Re-read the page without disturbing the viewport or the card being edited. */
  async function reload() {
    if (!noteId.value) return
    const ids = blocks.value.map((b) => b.block.id)
    const page = await pageApi.get(noteId.value, ids.length ? ids : undefined)
    const seen = new Set(ids)
    const extra = page.blocks.filter((b) => !seen.has(b.block.id))
    blocks.value = [...page.blocks, ...extra]
    noteTitle.value = page.noteTitle
    tags.value = page.tags
  }

  function requireCapacity(adding = 1): boolean {
    if (count.value + adding <= maxBlocks.value) return true
    ui.toast(
      `每篇笔记最多 ${maxBlocks.value} 张卡片，当前 ${count.value} 张。` +
        `请新建一篇笔记，或先合并/删除部分卡片。`,
      'error',
      5200,
    )
    return false
  }

  // ---------------------------------------------------------------- selection

  /**
   * 用户**手动调过高度**的卡片：自动贴合不再动它们。
   *
   * <p>自动贴合是按「内容 + 视口封顶」算的，一旦每次都跑，用户调好的长度会在切走再切回、
   * 或继续打字时"弹回"视口长度。手动调过就锁定（本会话有效；重新加载后若还想保留长度，
   * 只要不再编辑内容就不会被覆盖）。
   */
  const manualHeights = ref<Set<string>>(new Set())
  function markManualHeight(blockId: string) {
    manualHeights.value = new Set(manualHeights.value).add(blockId)
  }
  function isManualHeight(blockId: string) {
    return manualHeights.value.has(blockId)
  }

  function select(ids: string[], additive = false) {
    if (additive) {
      const set = new Set(selection.value)
      for (const id of ids) {
        if (set.has(id)) set.delete(id)
        else set.add(id)
      }
      selection.value = [...set]
    } else {
      selection.value = [...ids]
    }
  }

  function clearSelection() {
    selection.value = []
  }

  function selectAll() {
    selection.value = blocks.value.map((b) => b.block.id)
  }

  // ---------------------------------------------------------------- geometry

  function localMove(patches: BlockPatch[]) {
    for (const p of patches) {
      const view = blocks.value.find((b) => b.block.id === p.id)
      if (!view) continue
      if (p.x !== undefined && p.x !== null) view.block.x = p.x
      if (p.y !== undefined && p.y !== null) view.block.y = p.y
      if (p.w !== undefined && p.w !== null) view.block.w = Math.max(BLOCK_MIN_W, p.w)
      if (p.h !== undefined && p.h !== null) view.block.h = Math.max(BLOCK_MIN_H, p.h)
      if (p.z !== undefined && p.z !== null) view.block.z = p.z
      if (p.color !== undefined) view.block.color = p.color
      if (p.status !== undefined) view.block.status = p.status
      if (p.collapsed !== undefined && p.collapsed !== null) view.block.collapsed = p.collapsed
    }
  }

  async function commit(patches: BlockPatch[]) {
    if (!noteId.value || patches.length === 0) return
    localMove(patches)
    try {
      await pageApi.patchBlocks(noteId.value, patches)
    } catch (e) {
      ui.toast(`页面保存失败：${(e as Error).message}`, 'error')
      await reload()
    }
  }

  /**
   * Card width for new cards.
   *
   * Cards are wide enough to write in, but never so wide that the page has no blank area left
   * to click: the layout keeps at least ~120px of free space, and a margin on the right so the
   * card's resize handle stays reachable.
   */
  function defaultCardWidth(surfaceWidth = 1100): number {
    const usable = surfaceWidth - LIMITS.minVisibleGap
    return Math.round(Math.max(320, Math.min(LIMITS.maxW, usable * LIMITS.widthRatio)))
  }

  function defaultCardHeight(): number {
    return LIMITS.defaultH
  }

  /** Clamp every card to the current surface width (called when the viewport resizes). */
  function clampWidths(surfaceWidth: number) {
    const max = Math.max(320, Math.min(LIMITS.maxW, surfaceWidth - LIMITS.minVisibleGap))
    const patches: BlockPatch[] = []
    for (const v of blocks.value) {
      if (v.block.w > max) {
        v.block.w = max
        patches.push({ id: v.block.id, w: max })
      }
    }
    if (patches.length && noteId.value) {
      void pageApi.patchBlocks(noteId.value, patches).catch(() => undefined)
    }
  }

  async function addBlock(
    at: { x: number; y: number },
    opts: { contentMd?: string; title?: string; w?: number; h?: number; surfaceWidth?: number } = {},
  ): Promise<NoteBlockView | null> {
    if (!noteId.value) return null
    if (!requireCapacity()) return null
    try {
      const created = await pageApi.addBlock(noteId.value, {
        x: Math.round(at.x),
        y: Math.round(at.y),
        w: opts.w ?? defaultCardWidth(opts.surfaceWidth),
        h: opts.h ?? defaultCardHeight(),
        contentMd: opts.contentMd ?? '',
        title: opts.title ?? '',
      })
      await reload()
      // 结构性变化要同步进总数缓存：removeIfEmpty 靠它判断「至少保留一张卡」，
      // 缓存停在 load() 时的旧值会让空卡回收被误拦。
      totalCards.value++
      // reload() 只按已装载的 ids 拉（懒加载分页），新建的卡不在其中 —— 不补进列表它就不会
      // 渲染，双击建卡看起来「没反应」，打进去的字也随编辑态一起丢掉。
      let fresh = blocks.value.find((b) => b.block.id === created.block.id)
      if (!fresh) {
        blocks.value.push(created)
        fresh = created
      }
      selection.value = [fresh.block.id]
      return fresh
    } catch (e) {
      ui.toast((e as Error).message, 'error')
      return null
    }
  }

  /**
   * Remove a card that holds nothing. Called when a card is left with neither text nor a
   * title: an empty card is just noise on the page.
   *
   * The page always keeps at least one card, so the last remaining card is cleared instead.
   */
  async function removeIfEmpty(blockId: string): Promise<boolean> {
    if (!noteId.value) return false
    const view = blocks.value.find((b) => b.block.id === blockId)
    if (!view) return false
    if ((view.block.contentMd ?? '').trim() || (view.block.title ?? '').trim()) return false
    if (count.value <= 1) return false
    try {
      await pageApi.removeBlock(noteId.value, blockId, false)
      totalCards.value--
      await reload()
      selection.value = selection.value.filter((id) => id !== blockId)
      return true
    } catch {
      return false
    }
  }

  /** Card size that fits pasted text, so nothing is cropped on arrival. */
  function sizeForContent(content: string): { w: number; h: number } {
    const lines = content.split('\n')
    const longest = lines.reduce((max, l) => Math.max(max, l.length), 0)
    const cjk = (content.match(/[\u3400-\u9fff]/g) || []).length
    const visual = Math.max(longest, content.length / 2) + cjk / 4
    // Cards are wide by default (a card is written in place, so it needs room), but never
    // wider than the page allows.
    const w = Math.round(Math.min(LIMITS.maxW, Math.max(420, 260 + visual * 4.2)))
    const perLine = Math.max(20, Math.floor((w - 40) / 8.4))
    const estLines = Math.min(30, Math.max(lines.length, Math.ceil(content.length / perLine)))
    const h = Math.round(Math.min(520, Math.max(180, 84 + estLines * 24)))
    return { w, h }
  }

  async function addBlockFromContent(content: string, at: { x: number; y: number }) {
    const trimmed = content.replace(/\r\n/g, '\n').trim()
    const { w, h } = sizeForContent(trimmed)
    return addBlock(at, { contentMd: trimmed, w, h })
  }

  /**
   * Write markdown typed inside a card. The card owns this text; the note follows.
   *
   * <p>请求带上「编辑开始时卡片上的正文」作为基线：磁盘上已经不是那一份（外部编辑器改过）时
   * 后端回 409，这里提示并重新载入 —— **磁盘优先**，这次保存不会覆盖外部改动。
   */
  async function writeContent(blockId: string, contentMd: string) {
    if (!noteId.value) return
    const view = blocks.value.find((b) => b.block.id === blockId)
    if (!view) return
    savingContent.value = blockId
    // 基线必须在乐观改写之前取：它就是这次编辑开始时用户看到的正文。
    const baseContentMd = view.block.contentMd ?? ''
    // Reflect the text before the round trip: writing a card ends its edit, and the trailing
    // blur must see the new text so it does not send the same content again.
    view.block.contentMd = contentMd
    view.contentMd = contentMd
    try {
      const updated = await pageApi.writeContent(noteId.value, blockId, contentMd, baseContentMd)
      if (updated.noteTitle) noteTitle.value = updated.noteTitle
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        ui.toast('这张卡片已被外部修改，已重新载入磁盘上的内容', 'error', 5200)
        await reload()
      } else {
        // A card deleted while its content was still in flight is an expected race, not an
        // error worth shouting about.
        const message = (e as Error).message
        if (!message.includes('block')) {
          ui.toast(`保存失败：${message}`, 'error')
        }
      }
    } finally {
      savingContent.value = null
      editingBlockId.value = null
    }
  }

  async function removeBlock(blockId: string, deleteContent = false) {
    if (!noteId.value) return
    await pageApi.removeBlock(noteId.value, blockId, deleteContent)
    totalCards.value--
    // The remaining cards keep the positions the user gave them; the gap stays open.
    await reload()
    selection.value = selection.value.filter((id) => id !== blockId)
  }

  async function removeSelected() {
    if (!selection.value.length) return
    const ids = [...selection.value]
    // Removing the only card would leave the page empty, so keep the last one.
    const keep = count.value - ids.length
    const idsToDelete = keep < 1 ? ids.slice(0, ids.length - 1) : ids
    if (!idsToDelete.length) {
      ui.toast('页面至少需要保留一张卡片', 'error')
      return
    }
    for (const id of idsToDelete) {
      await pageApi.removeBlock(noteId.value!, id, false)
    }
    totalCards.value -= idsToDelete.length
    // Re-read rather than trusting the local list: the server re-flows the column, so
    // positions of the surviving cards change too.
    await reload()
    selection.value = []
    if (idsToDelete.length < ids.length) {
      ui.toast('已保留最后一张卡片（页面不能为空）')
    }
  }

  async function duplicateSelected(dx = GRID * 2, dy = GRID * 2) {
    if (!noteId.value || !selection.value.length) return
    if (!requireCapacity()) return
    const created: NoteBlock[] = []
    for (const id of selection.value) {
      created.push(await pageApi.duplicateBlock(noteId.value, id, dx, dy))
    }
    await reload()
    selection.value = created.map((b) => b.id)
  }

  async function zOrder(op: 'top' | 'bottom' | 'up' | 'down') {
    if (!noteId.value || !selection.value.length) return
    for (const id of selection.value) {
      await pageApi.zOrder(noteId.value, id, op)
    }
    await reload()
  }

  async function setColor(color: string | null) {
    if (!selection.value.length) return
    await commit(selection.value.map((id) => ({ id, color })))
  }

  // ---------------------------------------------------------------- viewport

  function screenToWorld(p: { x: number; y: number }) {
    const vp = viewport.value
    return { x: vp.x + p.x / vp.zoom, y: vp.y + p.y / vp.zoom }
  }

  function zoomAt(screen: { x: number; y: number }, factor: number) {
    const vp = viewport.value
    const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, vp.zoom * factor))
    if (next === vp.zoom) return
    const wx = vp.x + screen.x / vp.zoom
    const wy = vp.y + screen.y / vp.zoom
    viewport.value = { zoom: next, x: wx - screen.x / next, y: wy - screen.y / next }
  }

  /** Fit every card into the viewport (the page "zoom to fit" button). */
  function fit(size: { width: number; height: number }) {
    const list = blocks.value.map((b) => b.block)
    if (!list.length) {
      viewport.value = { x: 0, y: 0, zoom: 1 }
      return
    }
    const minX = Math.min(...list.map((b) => b.x))
    const minY = Math.min(...list.map((b) => b.y))
    const maxX = Math.max(...list.map((b) => b.x + b.w))
    const maxY = Math.max(...list.map((b) => b.y + b.h))
    const pad = 60
    const zoom = Math.min(
      1.4,
      Math.max(
        MIN_ZOOM,
        Math.min(
          (size.width - pad * 2) / Math.max(1, maxX - minX),
          (size.height - pad * 2) / Math.max(1, maxY - minY),
        ),
      ),
    )
    viewport.value = {
      zoom,
      x: minX - (size.width / zoom - (maxX - minX)) / 2,
      y: minY - (size.height / zoom - (maxY - minY)) / 2,
    }
  }

  function resetView() {
    viewport.value = { x: 0, y: 0, zoom: 1 }
  }

  function toggleGrid() {
    gridVisible.value = !gridVisible.value
    localStorage.setItem('thisnote.page.grid', gridVisible.value ? 'on' : 'off')
  }

  function toggleSnap() {
    snapToGrid.value = !snapToGrid.value
    localStorage.setItem('thisnote.page.snap', snapToGrid.value ? 'on' : 'off')
  }

  /** Bounding box of all cards, for the minimap-less "fit" maths and the status bar. */
  const bounds = computed(() => {
    const list = blocks.value.map((b) => b.block)
    if (!list.length) return { x: 0, y: 0, w: 900, h: 600 }
    const minX = Math.min(...list.map((b) => b.x))
    const minY = Math.min(...list.map((b) => b.y))
    const maxX = Math.max(...list.map((b) => b.x + b.w))
    const maxY = Math.max(...list.map((b) => b.y + b.h))
    return { x: minX, y: minY, w: Math.max(1, maxX - minX), h: Math.max(1, maxY - minY) }
  })

  return {
    noteId,
    noteTitle,
    blocks,
    tags,
    maxBlocks,
    loading,
    savingContent,
    editingBlockId,
    selection,
    selectedBlocks,
    singleSelected,
    viewport,
    gridVisible,
    snapToGrid,
    count,
    pendingCount,
    sentinelY,
    full,
    remaining,
    limitLabel,
    bounds,
    load,
    loadMore,
    reload,
    requireCapacity,
    select,
    clearSelection,
    selectAll,
    localMove,
    commit,
    addBlock,
    addBlockFromContent,
    removeIfEmpty,
    sizeForContent,
    defaultCardWidth,
    defaultCardHeight,
    clampWidths,
    writeContent,
    removeBlock,
    removeSelected,
    duplicateSelected,
    zOrder,
    markManualHeight,
    isManualHeight,
    setColor,
    screenToWorld,
    zoomAt,
    fit,
    resetView,
    toggleGrid,
    toggleSnap,
  }
})
