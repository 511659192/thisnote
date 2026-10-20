import { computed, reactive, ref, type Ref } from 'vue'
import { BLOCK_MIN_H, BLOCK_MIN_W, GRID, usePageStore } from '@/stores/notePage'
import type { CardView } from '@/api/types'
import type { CardUpdate } from '@/api/page'

type DragMode = 'none' | 'pan' | 'marquee' | 'move' | 'resize'
type PageStore = ReturnType<typeof usePageStore>

export function usePageInteractions(
  root: Ref<HTMLElement | null>,
  page: PageStore,
  finishEditing: () => Promise<void>,
) {
  const lastPointer = ref({ x: 240, y: 160 })
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

  function localPoint(e: PointerEvent | MouseEvent | WheelEvent) {
    const rect = root.value!.getBoundingClientRect()
    return { x: e.clientX - rect.left, y: e.clientY - rect.top }
  }

  function snap(value: number) {
    return page.snapToGrid ? Math.round(value / GRID) * GRID : Math.round(value)
  }

  function onWheel(e: WheelEvent) {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault()
      page.zoomAt(localPoint(e), e.deltaY < 0 ? 1.1 : 1 / 1.1)
      return
    }
    const scroller = (e.target as HTMLElement | null)?.closest?.('.body') as HTMLElement | null
    if (scroller && scroller.scrollHeight > scroller.clientHeight + 1) return
    e.preventDefault()
    const viewport = page.viewport
    const dx = e.shiftKey ? e.deltaY : e.deltaX
    const dy = e.shiftKey ? 0 : e.deltaY
    page.viewport = { ...viewport, x: viewport.x + dx / viewport.zoom, y: viewport.y + dy / viewport.zoom }
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
    const point = localPoint(e)
    drag.mode = 'marquee'
    drag.startScreen = point
    marquee.active = true
    Object.assign(marquee, { x: point.x, y: point.y, w: 0, h: 0 })
  }

  function onSurfacePointerMove(e: PointerEvent) {
    lastPointer.value = localPoint(e)
  }

  function onBlockPointerDown(payload: {
    event: PointerEvent
    view: CardView
    mode: 'move' | 'resize'
    handle?: string
  }) {
    const { event, view, mode } = payload
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
    drag.handle = payload.handle ?? 'se'
    drag.startScreen = localPoint(event)
    drag.startWorld = page.screenToWorld(drag.startScreen)
    drag.origin = {}
    for (const selectedId of page.selection) {
      const selected = page.cards.find((block) => block.card.cardId === selectedId)
      if (selected) drag.origin[selectedId] = { x: selected.card.x, y: selected.card.y, w: selected.card.w, h: selected.card.h }
    }
    drag.moved = false
    drag.pending.clear()
  }

  function onPointerMove(e: PointerEvent) {
    if (drag.mode === 'none') return
    const point = localPoint(e)
    const world = page.screenToWorld(point)
    const dxScreen = point.x - drag.startScreen.x
    const dyScreen = point.y - drag.startScreen.y
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
      marquee.x = Math.min(drag.startScreen.x, point.x)
      marquee.y = Math.min(drag.startScreen.y, point.y)
      marquee.w = Math.abs(dxScreen)
      marquee.h = Math.abs(dyScreen)
      const start = page.screenToWorld({ x: marquee.x, y: marquee.y })
      const end = page.screenToWorld({ x: marquee.x + marquee.w, y: marquee.y + marquee.h })
      page.select(
        page.cards
          .filter((view) =>
            view.card.x + view.card.w >= start.x &&
            view.card.y + view.card.h >= start.y &&
            view.card.x <= end.x &&
            view.card.y <= end.y,
          )
          .map((view) => view.card.cardId),
      )
      return
    }

    if (drag.mode === 'move') {
      const id = page.selection[0]
      const origin = drag.origin[id]
      const view = page.cards.find((block) => block.card.cardId === id)
      if (!origin || !view) return
      const x = snap(origin.x + (world.x - drag.startWorld.x))
      const y = snap(origin.y + (world.y - drag.startWorld.y))
      page.localMove([{ cardId: view.card.cardId, x, y, w: origin.w, h: origin.h }])
      drag.pending.set(id, { x, y, w: origin.w, h: origin.h })
      return
    }

    const id = Object.keys(drag.origin)[0]
    const origin = drag.origin[id]
    const view = page.cards.find((block) => block.card.cardId === id)
    if (!origin || !view) return
    const dx = world.x - drag.startWorld.x
    const dy = world.y - drag.startWorld.y
    const handle = drag.handle
    let x = origin.x
    let y = origin.y
    let w = origin.w
    let h = origin.h
    if (handle.includes('e')) w = Math.max(BLOCK_MIN_W, origin.w + dx)
    if (handle.includes('s')) h = Math.max(BLOCK_MIN_H, origin.h + dy)
    if (handle.includes('w')) {
      w = Math.max(BLOCK_MIN_W, origin.w - dx)
      x = origin.x + (origin.w - w)
    }
    if (handle.includes('n')) {
      h = Math.max(BLOCK_MIN_H, origin.h - dy)
      y = origin.y + (origin.h - h)
    }
    page.localMove([{ cardId: view.card.cardId, x: snap(x), y: snap(y), w: Math.round(w), h: Math.round(h) }])
    drag.pending.set(id, { x: snap(x), y: snap(y), w: Math.round(w), h: Math.round(h) })
  }

  async function onPointerUp() {
    const mode = drag.mode
    const moved = drag.moved
    drag.mode = 'none'
    marquee.active = false
    if ((mode === 'move' || mode === 'resize') && moved && drag.pending.size) {
      const updates: CardUpdate[] = [...drag.pending.entries()].flatMap(([id, geometry]) => {
        const view = page.cards.find((block) => block.card.cardId === id)
        return view ? [{ cardId: view.card.cardId, x: geometry.x, y: geometry.y, w: geometry.w, h: geometry.h }] : []
      })
      if (mode === 'resize' && drag.handle && /[ns]/.test(drag.handle)) {
        for (const id of drag.pending.keys()) page.markManualHeight(id)
      }
      drag.pending.clear()
      await page.commit(updates)
    }
  }

  return {
    worldStyle,
    lastPointer,
    marquee,
    localPoint,
    onWheel,
    onSurfacePointerDown,
    onSurfacePointerMove,
    onBlockPointerDown,
    onPointerMove,
    onPointerUp,
  }
}
