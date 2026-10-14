<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { BLOCK_MIN_H, BLOCK_MIN_W, GRID, MAX_ZOOM, MIN_ZOOM, maxCardWidth, usePageStore } from '@/stores/notePage'
import NoteCard from './NoteCard.vue'
import { activeCardPlugins, toast } from '@thisnode/plugin-api'
import { deleteUnreferencedImageAttachment, getClipboardImage, getImageDimensions, uploadImageAttachment } from './clipboardImage'
import { getClipboardMarkdown, getClipboardMarkdownFile, isClipboardCode } from './markdownFormat'
import type { CardView } from '@/api/types'
import type { CardUpdate } from '@/api/page'

/**
 * A note's page: a single column of cards that grows downwards.
 *
 * Interaction (OneNote-like, not a whiteboard):
 *   * double-click / click empty space → a new card appears there and is ready for typing;
 *     the clicked point becomes the card's top-left corner
 *   * Ctrl+V                          → the clipboard becomes a card
 *   * type in a card                  → written straight into the note
 *   * empty cards remain until explicitly deleted or their note is deleted
 *
 * There is no dot grid, no groups, no connectors, no board concept.
 */
const page = usePageStore()

const emit = defineEmits<{
  (e: 'context', p: { x: number; y: number; target: 'page' | 'card'; view?: CardView; imageSrc?: string }): void
}>()

const root = ref<HTMLElement | null>(null)
const sentinel = ref<HTMLElement | null>(null)
const size = reactive({ width: 1100, height: 700 })
const lastPointer = ref({ x: 240, y: 160 })

type DragMode = 'none' | 'pan' | 'marquee' | 'move' | 'resize'
const drag = reactive({
  mode: 'none' as DragMode,
  startScreen: { x: 0, y: 0 },
  startWorld: { x: 0, y: 0 },
  startViewport: { x: 0, y: 0 },
  origin: {} as Record<string, { x: number; y: number; w: number; h: number }>,
  handle: 'se',
  moved: false,
  pending: new Map<string, { x: number; y: number; w: number; h: number }>(),
})

const marquee = reactive({ active: false, x: 0, y: 0, w: 0, h: 0 })

const worldStyle = computed(() => ({
  transform: `scale(${page.viewport.zoom}) translate3d(${-page.viewport.x}px, ${-page.viewport.y}px, 0)`,
  transformOrigin: '0 0',
}))

// ---------------------------------------------------------------- lifecycle

let observer: ResizeObserver | null = null
/** 下一页的哨兵：靠近视口就取下一批卡片（几何 + 正文）。 */
let sentinelObserver: IntersectionObserver | null = null

function watchSentinel() {
  sentinelObserver?.disconnect()
  const el = sentinel.value
  if (!el) return
  sentinelObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) void page.loadMore()
    },
    { root: root.value, rootMargin: '600px 0px' },
  )
  sentinelObserver.observe(el)
}

// 插件底座：卡片集合或插件注册变化时，批量装载对应状态（每页一次请求）。
// 插件微应用可能晚于笔记页挂载；也要监听插件实例替换，避免热重载后状态没补拉。
// 只问仍在运行的插件——被停用的插件其端点会被拒绝，问了也只是白打一串 409。
watch(
  [
    () => page.cards.map((b) => b.card.cardId).join(','),
    () => activeCardPlugins(),
  ],
  ([ids]) => {
    if (!ids) return
    const cardIds = ids.split(',')
    for (const plugin of activeCardPlugins()) {
      void plugin.loadState(cardIds).catch(() => undefined)
    }
  },
  { immediate: true },
)

onMounted(() => {
  if (!root.value) return
  const rect = root.value.getBoundingClientRect()
  size.width = rect.width
  size.height = rect.height
  observer = new ResizeObserver((entries) => {
    for (const e of entries) {
      size.width = e.contentRect.width
      size.height = e.contentRect.height
    }
    // Cards never outgrow the visible page, otherwise there is no blank space left to click.
    page.clampWidths(size.width)
  })
  observer.observe(root.value)
  page.clampWidths(size.width)
  root.value.addEventListener('wheel', onWheel, { passive: false })
  window.addEventListener('pointerdown', onWindowPointerDown, true)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('paste', onPaste)
})

// 哨兵随「还剩几张卡」创建/销毁，所以每次挂载后再下一次 tick 重新观察一次。
onMounted(() => {
  void nextTick(watchSentinel)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  root.value?.removeEventListener('wheel', onWheel)
  window.removeEventListener('pointerdown', onWindowPointerDown, true)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('paste', onPaste)
})

function localPoint(e: PointerEvent | MouseEvent | WheelEvent) {
  const rect = root.value!.getBoundingClientRect()
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}

/** Cards snap to the page grid only for resizing; placement comes from the flow. */
function snap(v: number) {
  return page.snapToGrid ? Math.round(v / GRID) * GRID : Math.round(v)
}

// ---------------------------------------------------------------- zoom / pan

function onWheel(e: WheelEvent) {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    page.zoomAt(localPoint(e), e.deltaY < 0 ? 1.1 : 1 / 1.1)
    return
  }
  // 长文卡片超出视口的部分靠卡片内滚动阅读：滚轮落在能滚的卡片体上就交给浏览器。
  const scroller = (e.target as HTMLElement | null)?.closest?.('.body') as HTMLElement | null
  if (scroller && scroller.scrollHeight > scroller.clientHeight + 1) {
    return
  }
  e.preventDefault()
  const vp = page.viewport
  const dx = e.shiftKey ? e.deltaY : e.deltaX
  const dy = e.shiftKey ? 0 : e.deltaY
  page.viewport = { ...vp, x: vp.x + dx / vp.zoom, y: vp.y + dy / vp.zoom }
}

function onSurfacePointerDown(e: PointerEvent) {
  if ((e.target as HTMLElement).closest('.card')) return
  if (e.button === 0 && e.altKey) {
    startPan(e)
    return
  }
  if (e.button !== 0) return
  if (e.shiftKey) {
    startMarquee(e)
    return
  }
  // Clicking the surface blurs the open card, whose blur handler saves and prunes it.
  page.clearSelection()
  startPan(e)
}

function startPan(e: PointerEvent) {
  drag.mode = 'pan'
  drag.startScreen = localPoint(e)
  drag.startViewport = { x: page.viewport.x, y: page.viewport.y }
  drag.moved = false
}

function startMarquee(e: PointerEvent) {
  const p = localPoint(e)
  drag.mode = 'marquee'
  drag.startScreen = p
  marquee.active = true
  Object.assign(marquee, { x: p.x, y: p.y, w: 0, h: 0 })
}

function onSurfacePointerMove(e: PointerEvent) {
  lastPointer.value = localPoint(e)
}

/**
 * Any pointer press outside the card being edited ends that edit. Attached at the window level
 * (capture phase) rather than on the surface, because the press may land on a card further
 * down, on the toolbar, or outside the page entirely — all of which should still save and
 * prune the card the user just abandoned.
 */
function onWindowPointerDown(e: PointerEvent) {
  const id = page.editingCardId
  if (!id) return
  const target = e.target as HTMLElement | null
  if (target && target.closest('.card.editing')) return
  void finishEditing(id)
}

// ---------------------------------------------------------------- card drag

function onBlockPointerDown(p: {
  event: PointerEvent
  view: CardView
  mode: 'move' | 'resize'
  handle?: string
}) {
  const { event, view, mode } = p
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  if (page.editingCardId && page.editingCardId !== view.card.cardId) {
    void finishEditing()
  }

  const id = view.card.cardId
  const additive = event.shiftKey || event.metaKey || event.ctrlKey
  if (!page.selection.includes(id)) {
    page.select([id], additive)
  } else if (additive) {
    page.select([id], true)
  } else {
    page.select([id])
  }
  if (!page.selection.includes(id)) return

  drag.mode = mode
  drag.handle = p.handle ?? 'se'
  drag.startScreen = localPoint(event)
  drag.startWorld = page.screenToWorld(drag.startScreen)
  drag.origin = {}
  for (const selId of page.selection) {
    const v = page.cards.find((b) => b.card.cardId === selId)
    if (v) drag.origin[selId] = { x: v.card.x, y: v.card.y, w: v.card.w, h: v.card.h }
  }
  drag.moved = false
  drag.pending.clear()
}

function onPointerMove(e: PointerEvent) {
  if (drag.mode === 'none') return
  const p = localPoint(e)
  const world = page.screenToWorld(p)
  const dxScreen = p.x - drag.startScreen.x
  const dyScreen = p.y - drag.startScreen.y
  if (Math.abs(dxScreen) + Math.abs(dyScreen) > 3) drag.moved = true

  if (drag.mode === 'pan') {
    page.viewport = {
      ...page.viewport,
      x: drag.startViewport.x - dxScreen / page.viewport.zoom,
      y: drag.startViewport.y - dyScreen / page.viewport.zoom,
    }
    return
  }

  if (drag.mode === 'marquee') {
    marquee.x = Math.min(drag.startScreen.x, p.x)
    marquee.y = Math.min(drag.startScreen.y, p.y)
    marquee.w = Math.abs(dxScreen)
    marquee.h = Math.abs(dyScreen)
    const a = page.screenToWorld({ x: marquee.x, y: marquee.y })
    const b = page.screenToWorld({ x: marquee.x + marquee.w, y: marquee.y + marquee.h })
    page.select(
      page.cards
        .filter(
          (v) =>
            v.card.x + v.card.w >= a.x &&
            v.card.y + v.card.h >= a.y &&
            v.card.x <= b.x &&
            v.card.y <= b.y,
        )
        .map((v) => v.card.cardId),
    )
    return
  }

  if (drag.mode === 'move') {
    // Free positioning: dragging the grip moves the card anywhere on the page, x and y alike.
    // Cards may overlap — nothing re-packs them afterwards.
    const world = page.screenToWorld(p)
    const id = page.selection[0]
    const o = drag.origin[id]
    const view = page.cards.find((b) => b.card.cardId === id)
    if (!o || !view) return
    const x = snap(o.x + (world.x - drag.startWorld.x))
    const y = snap(o.y + (world.y - drag.startWorld.y))
    page.localMove([{ cardId: view.card.cardId, x, y, w: o.w, h: o.h }])
    drag.pending.set(id, { x, y, w: o.w, h: o.h })
    return
  }

  if (drag.mode === 'resize') {
    const id = Object.keys(drag.origin)[0]
    const o = drag.origin[id]
    const view = page.cards.find((b) => b.card.cardId === id)
    if (!o || !view) return
    const dx = world.x - drag.startWorld.x
    const dy = world.y - drag.startWorld.y
    const h = drag.handle
    let x = o.x
    let y = o.y
    let w = o.w
    let hh = o.h
    if (h.includes('e')) w = Math.max(BLOCK_MIN_W, o.w + dx)
    if (h.includes('s')) hh = Math.max(BLOCK_MIN_H, o.h + dy)
    if (h.includes('w')) {
      w = Math.max(BLOCK_MIN_W, o.w - dx)
      x = o.x + (o.w - w)
    }
    if (h.includes('n')) {
      hh = Math.max(BLOCK_MIN_H, o.h - dy)
      y = o.y + (o.h - hh)
    }
    page.localMove([{ cardId: view.card.cardId, x: snap(x), y: snap(y), w: Math.round(w), h: Math.round(hh) }])
    drag.pending.set(id, { x: snap(x), y: snap(y), w: Math.round(w), h: Math.round(hh) })
  }
}

async function onPointerUp() {
  const mode = drag.mode
  const moved = drag.moved
  drag.mode = 'none'
  marquee.active = false
  if ((mode === 'move' || mode === 'resize') && moved && drag.pending.size) {
    const updates: CardUpdate[] = [...drag.pending.entries()].flatMap(([id, g]) => {
      const view = page.cards.find((b) => b.card.cardId === id)
      return view ? [{ cardId: view.card.cardId, x: g.x, y: g.y, w: g.w, h: g.h }] : []
    })
    // 拖过下边框/边角改高度的卡片 → 长度由用户做主，自动贴合不再覆盖（否则会"弹回"）。
    if (mode === 'resize' && drag.handle && /[ns]/.test(drag.handle)) {
      for (const id of drag.pending.keys()) page.markManualHeight(id)
    }
    drag.pending.clear()
    await page.commit(updates)
  }
}

// ---------------------------------------------------------------- editing

async function beginEdit(view: CardView) {
  page.editingCardId = view.card.cardId
  page.select([view.card.cardId])
}

/**
 * Text most recently handed over by a card's blur handler, together with the save request
 * it started. `finishEditing` awaits the request before ending edit mode, so writes stay ordered.
 */
const lastSave = reactive(new Map<string, Promise<void>>())

/**
 * The live text of the card being edited, read straight from its textarea.
 *
 * <p>This cannot come from the store or from `lastSave`: the pointer press that leaves a card
 * fires *before* the textarea's `blur`, so at this moment neither has seen the typed text yet.
 * Reading the DOM is what makes "type, then click away" save instead of silently dropping the
 * edit — otherwise typing and clicking away could silently lose the latest text.
 */
function currentEditorText(cardId: string): string | null {
  const el = root.value?.querySelector<HTMLTextAreaElement>(
    `article.card[data-card-id="${CSS.escape(cardId)}"] textarea.body.edit`,
  )
  return el ? el.value : null
}

/** Finish editing the current card only after pending writes have completed. */
async function finishEditing(cardId?: string) {
  const id = cardId ?? page.editingCardId
  if (!id) return
  const view = page.cards.find((b) => b.card.cardId === id)
  if (!view) {
    page.editingCardId = null
    return
  }
  const live = currentEditorText(id)
  await nextTick()
  // Prefer the blur handler's save when it already ran; otherwise save the live text now and
  // await it before completing the edit.
  let pending = lastSave.get(id)
  if (!pending && live !== null) {
    // Record the write before starting it, so the blur that follows this very press finds a
    // save already in flight and does not send the same text a second time.
    pending = page.writeContent(id, live)
    lastSave.set(id, pending)
  }
  if (pending) {
    await pending
    if (lastSave.get(id) === pending) lastSave.delete(id)
  }
  if (page.editingCardId === id) page.editingCardId = null
}

function onSave(p: { cardId: string; contentMd: string }) {
  // Writing a card's content ends its edit, which unmounts the textarea and fires one last blur
  // carrying the same text. Ignoring a save that would write what the card already holds stops
  // that trailing blur from sending a duplicate request.
  const view = page.cards.find((b) => b.card.cardId === p.cardId)
  if (view && view.contentMd === p.contentMd) {
    lastSave.set(p.cardId, Promise.resolve())
    return Promise.resolve()
  }
  const pending = page.writeContent(p.cardId, p.contentMd)
  lastSave.set(p.cardId, pending)
  return pending
}

/**
 * Grow a card downwards to fit text the user just typed. Width is untouched: the card keeps the
 * width and left edge the user chose, and only the height follows the content.
 */
function onToggleCollapse(cardId: string) {
  const view = page.cards.find((b) => b.card.cardId === cardId)
  if (!view) return
  void page.commit([{ cardId: view.card.cardId, collapsed: !view.card.collapsed }])
}

function onFitHeight(p: { cardId: string; h: number; w?: number; fitImage?: boolean }) {
  const view = page.cards.find((b) => b.card.cardId === p.cardId)
  if (!view) return
  // 用户手动定过长度的卡片不再自动贴合（说了算的是用户，不是内容）。
  if (page.isManualHeight(p.cardId) && !p.fitImage) return
  const width = p.w === undefined
    ? view.card.w
    : Math.max(BLOCK_MIN_W, Math.min(p.w, maxCardWidth(size.width)))
  if (width !== view.card.w) {
    void page.commit([{ cardId: view.card.cardId, w: width }]).catch(() => undefined)
    return
  }
  // 双向贴合（长高也缩回），再按视口封顶：卡片最高长到**浏览器底缘**，
  // 再多的内容在卡片内滚动，而不是把卡片撑出好几屏、在画布上够不着。
  const rect = root.value?.getBoundingClientRect()
  const screenTop = (rect?.top ?? 0) + (view.card.y - page.viewport.y) * page.viewport.zoom
  const available = Math.max(
    BLOCK_MIN_H * 2,
    Math.round((window.innerHeight - screenTop - 12) / Math.max(page.viewport.zoom, 0.01)),
  )
  const next = Math.min(Math.round(p.h), available)
  if (next === view.card.h) return
  void page.commit([{ cardId: view.card.cardId, h: next }]).catch(() => undefined)
}

/** Double-click empty space: a card whose top-left corner is the clicked point. */
async function onDoubleClick(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('.card')) return
  const world = page.screenToWorld(localPoint(e))
  // The clicked point is the card's left/top edge, so the card lands where the user aimed, and
  // its width is sized to the visible page so a blank strip always remains.
  const created = await page.addBlock(
    { x: Math.round(world.x), y: Math.round(world.y) },
    { surfaceWidth: size.width / page.viewport.zoom },
  )
  if (created) await beginEdit(created)
}

// ---------------------------------------------------------------- clipboard

async function onPaste(e: ClipboardEvent) {
  const target = e.target as HTMLElement
  if (target.closest('textarea, input, .cm-editor')) return
  if (page.editingCardId) return

  const image = getClipboardImage(e.clipboardData)
  if (image) {
    e.preventDefault()
    const noteId = page.noteId
    if (!noteId) return
    const world = page.screenToWorld(lastPointer.value)
    const at = { x: Math.round(world.x), y: Math.round(world.y) }
    let markdown: string | null = null
    try {
      const imageSize = await getImageDimensions(image)
      markdown = await uploadImageAttachment(image)
      if (page.noteId !== noteId) {
        await deleteUnreferencedImageAttachment(markdown)
        return
      }
      const created = await page.addBlockFromContent(markdown, at, imageSize, size.width)
      if (created) toast('已粘贴图片为卡片', 'success')
      else await deleteUnreferencedImageAttachment(markdown)
    } catch (error) {
      if (markdown) void deleteUnreferencedImageAttachment(markdown).catch(() => undefined)
      toast(`粘贴图片失败：${error instanceof Error ? error.message : '上传图片失败'}`, 'error')
    }
    return
  }

  const markdownFile = getClipboardMarkdownFile(e.clipboardData)
  const clipboardCode = isClipboardCode(e.clipboardData)
  const hasHtml = Boolean(e.clipboardData?.getData('text/html').trim())
  const hasText = Boolean(e.clipboardData?.getData('text/plain').trim())
  if (!markdownFile && !hasHtml && !hasText) return
  e.preventDefault()

  const text = await getClipboardMarkdown(e.clipboardData)
  if (!text.trim()) return

  const world = page.screenToWorld(lastPointer.value)
  const at = { x: Math.round(world.x), y: Math.round(world.y) }
  const created = await page.addBlockFromContent(text, at)
  if (created) {
    const format = markdownFile ? 'markdown' : clipboardCode ? 'text' : null
    if (format) {
      try {
        localStorage.setItem(`thisnote.notes.card-format.${created.card.cardId}`, format)
      } catch {
        // The card content is still saved when browser storage is unavailable.
      }
    }
    toast('已粘贴为卡片', 'success')
  }
}

// ---------------------------------------------------------------- keyboard

function onKeydown(e: KeyboardEvent) {
  const target = e.target as HTMLElement
  if (['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable) {
    if (e.key === 'Escape') void finishEditing()
    return
  }
  const primary = navigator.platform.toLowerCase().includes('mac') ? e.metaKey : e.ctrlKey
  if ((primary && e.key.toLowerCase() === 'd') || e.key === 'Delete' || e.key === 'Backspace') {
    e.preventDefault()
    return
  }
  if (primary && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    page.selectAll()
  } else if (e.key === 'Escape') {
    if (page.editingCardId) void finishEditing()
    else page.clearSelection()
  } else if (e.key === 'F2' || e.key === 'Enter') {
    const single = page.singleSelected
    if (single) {
      e.preventDefault()
      void beginEdit(single)
    }
  } else if (primary && e.key === '0') {
    e.preventDefault()
    page.resetView()
  } else if (primary && e.key === '1') {
    e.preventDefault()
    page.fit(size)
  }
}

function onCardFocus(id: string) {
  page.select([id])
  page.bringToFront(id)
}

function onBlockContext(p: { event: MouseEvent; view: CardView; imageSrc?: string }) {
  if (!page.selection.includes(p.view.card.cardId)) page.select([p.view.card.cardId])
  emit('context', {
    x: p.event.clientX,
    y: p.event.clientY,
    target: 'card',
    view: p.view,
    imageSrc: p.imageSrc,
  })
}

function onSurfaceContext(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('.card')) return
  e.preventDefault()
  emit('context', { x: e.clientX, y: e.clientY, target: 'page' })
}

defineExpose({ size, beginEdit, finishEditing })
</script>

<template>
  <div
    ref="root"
    class="page-surface"
    @pointerdown="onSurfacePointerDown"
    @pointermove="onSurfacePointerMove"
    @dblclick="onDoubleClick"
    @contextmenu="onSurfaceContext"
  >
    <div class="world" :style="worldStyle">
      <NoteCard
        v-for="view in page.cards"
        :key="view.card.cardId"
        :view="view"
        :selected="page.selection.includes(view.card.cardId)"
        :editing="page.editingCardId === view.card.cardId"
        :focused="page.selection.includes(view.card.cardId)"
        :z-index="page.zIndexFor(view.card.cardId)"
        @pointerdown="onBlockPointerDown"
        @request-edit="
          (id: string) => {
            const v = page.cards.find((b) => b.card.cardId === id)
            if (v) beginEdit(v)
          }
        "
        @context="onBlockContext"
        @save="onSave"
        @fit-height="onFitHeight"
        @end-edit="finishEditing()"
        @toggle-collapse="onToggleCollapse"
        @focus="onCardFocus"
      />

      <!-- 「进入视口才拉」的哨兵：还有没取回来的卡片时存在，靠近它即取下一批 -->
      <div
        v-if="page.pendingCount > 0"
        ref="sentinel"
        class="load-sentinel"
        :style="{ top: `${page.sentinelY}px` }"
      ></div>
    </div>

    <div
      v-if="marquee.active"
      class="marquee"
      :style="{
        left: `${marquee.x}px`,
        top: `${marquee.y}px`,
        width: `${marquee.w}px`,
        height: `${marquee.h}px`,
      }"
    ></div>

    <div v-if="!page.cards.length && !page.loading" class="page-hint">
      <p>双击页面任意位置就能开始写</p>
    </div>

    <div class="zoom-bar">
      <button class="btn sm" title="缩小" @click="page.zoomAt({ x: size.width / 2, y: size.height / 2 }, 1 / 1.15)">−</button>
      <span class="small mono zoom-label">{{ Math.round(page.viewport.zoom * 100) }}%</span>
      <button class="btn sm" title="放大" @click="page.zoomAt({ x: size.width / 2, y: size.height / 2 }, 1.15)">＋</button>
      <button class="btn sm" title="适应全部卡片 (Ctrl+1)" @click="page.fit(size)">适应</button>
      <button class="btn sm" title="重置 (Ctrl+0)" @click="page.resetView()">1:1</button>
      <button class="btn sm" :class="{ active: page.snapToGrid }" title="尺寸吸附" @click="page.toggleSnap()">吸附</button>
    </div>
  </div>
</template>

<style scoped>
.page-surface {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  /* Plain surface: no dot grid (the user asked for the background hidden). */
  background-color: var(--c-surface);
  touch-action: none;
}
.world {
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
}
.marquee {
  position: absolute;
  border: 1px solid var(--accent);
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  pointer-events: none;
}
.load-sentinel {
  position: absolute;
  left: 0;
  width: 1px;
  height: 1px;
  pointer-events: none;
}

.page-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;  color: var(--c-text-3);
}
.zoom-bar {
  position: absolute;
  left: var(--space-4);
  bottom: var(--space-4);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow);
}
.zoom-label {
  min-width: 44px;
  text-align: center;
}
</style>
