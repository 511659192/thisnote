<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { BLOCK_MIN_H, BLOCK_MIN_W, fitCardMaxWidth, maxCardWidth, usePageStore } from '@/stores/notePage'
import NoteCard from './NoteCard.vue'
import NotePagePreview from './NotePagePreview.vue'
import { activeCardPlugins, createPreviewImageInteractions, host, onPluginStateChange, toast } from '@thisnote/plugin-api'
import { deleteUnreferencedImageAttachment, getClipboardImage, getImageDimensions, uploadImageAttachment } from './clipboardImage'
import { getClipboardMarkdown, getClipboardMarkdownFile, isClipboardCode } from './markdownFormat'
import type { CardView } from '@/api/types'
import type { CardUpdate } from '@/api/page'
import { usePageInteractions } from './usePageInteractions'

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

/** 预览浮层复用宿主的 Markdown 渲染（与卡片、复习页同一份）。 */
const { MarkdownView } = host()

const emit = defineEmits<{
  (e: 'context', p: { x: number; y: number; target: 'page' | 'card'; view?: CardView; imageSrc?: string }): void
}>()

const root = ref<HTMLElement | null>(null)
const sentinel = ref<HTMLElement | null>(null)
const size = reactive({ width: 1100, height: 700 })
const {
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
} = usePageInteractions(root, page, finishEditing)

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
function reloadCardPluginStates() {
  const cardIds = page.cards.map((b) => b.card.cardId)
  if (!cardIds.length) return
  for (const plugin of activeCardPlugins()) {
    void plugin.loadState(cardIds).catch(() => undefined)
  }
}

watch(
  [
    () => page.cards.map((b) => b.card.cardId).join(','),
    () => activeCardPlugins(),
  ],
  () => reloadCardPluginStates(),
  { immediate: true },
)

// 插件广播状态变化（例如列表里一键开启记忆曲线）：当前笔记的卡片徽标同步刷新
let unsubscribePluginState: (() => void) | null = null
onMounted(() => {
  unsubscribePluginState = onPluginStateChange((change) => {
    if (!activeCardPlugins().some((plugin) => plugin.id === change.pluginId)) return
    reloadCardPluginStates()
  })
})

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
  unsubscribePluginState?.()
  unsubscribePluginState = null
  disposePreviewInteractions()
  root.value?.removeEventListener('wheel', onWheel)
  window.removeEventListener('pointerdown', onWindowPointerDown, true)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('paste', onPaste)
})

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

/**
 * 选过预设宽度（1:1/百分比/自定义）的卡片：高度一直贴合内容，不再按视口封顶，避免出现滚动条。
 */
const fullHeightFits = new Set<string>()
/** 视口折算出的「100% 宽度」：卡片用它反推当前宽度落在哪个百分比档位。 */
const fitBaseWidth = computed(() => fitCardMaxWidth(size.width, page.viewport.zoom))
/** 新卡片默认宽度：视口的 50%（文本与图片一致）。 */
const defaultCardWidthPx = computed(() => Math.round(fitBaseWidth.value * 0.5))

function onFitHeight(p: { cardId: string; h: number; w?: number; fitImage?: boolean; force?: boolean }) {
  const view = page.cards.find((b) => b.card.cardId === p.cardId)
  if (!view) return
  // 「自动调整」由用户显式触发：先解除手动高度锁定，再照常贴合。
  if (p.force) page.clearManualHeight(p.cardId)
  // 用户手动定过长度的卡片不再自动贴合（说了算的是用户，不是内容）。
  else if (page.isManualHeight(p.cardId) && !p.fitImage) return
  // 选过宽度预设（含 1:1 按钮）的卡片永久取消高度封顶，后续图片加载/内容重排也不再截断
  if (p.force) fullHeightFits.add(p.cardId)
  // 图片卡（fitImage）不封顶：按原图 1:1 伸缩；文本卡仍受可视宽度约束。
  const imageCard = /!\[[^\]]*\]\(.+\)/.test(view.contentMd ?? '')
  // 高度封顶的豁免：
  //  - 宽度命中预设（刷新后也按当前宽度判断，不依赖本次点击）
  //  - 内容包含图片（按原图尺寸展示，卡片内不出现滚动条）
  //  - 本次选过自定义宽度 / 显式 1:1
  const base = fitBaseWidth.value
  const presetWidth = base > 0 && [0.25, 0.5, 0.75, 1]
    .some((ratio) => Math.abs(view.card.w - Math.round(base * ratio)) <= 2)
  const uncapped = presetWidth || imageCard || fullHeightFits.has(p.cardId)
  // 「自动调整」(force) 按内容宽度贴合，上限放宽到可视世界宽：常规上限下长行文本照旧换行，
  // 图片卡宽高算出来都等于现值，一次更新都不产生，按钮看起来完全失效。
  const widthCap = p.fitImage && imageCard
    ? Number.MAX_SAFE_INTEGER
    : p.force
      ? fitCardMaxWidth(size.width, page.viewport.zoom)
      : maxCardWidth(size.width)
  // 1:1 是内容驱动的显式贴合：宽度下限放宽（突破拖拽下限 240）以贴住短文本，
  // 卡片过窄时右侧工具行会被裁剪，拖拽右下角即可恢复常规宽度。
  const minWidth = p.force ? 88 : BLOCK_MIN_W
  const width = p.w === undefined
    ? view.card.w
    : Math.max(minWidth, Math.min(p.w, widthCap))
  // 双向贴合（长高也缩回），再按视口封顶：卡片最高长到**浏览器底缘**，
  // 再多的内容在卡片内滚动，而不是把卡片撑出好几屏、在画布上够不着。
  const rect = root.value?.getBoundingClientRect()
  const screenTop = (rect?.top ?? 0) + (view.card.y - page.viewport.y) * page.viewport.zoom
  const available = Math.max(
    BLOCK_MIN_H * 2,
    Math.round((window.innerHeight - screenTop - 12) / Math.max(page.viewport.zoom, 0.01)),
  )
  const next = uncapped ? Math.round(p.h) : Math.min(Math.round(p.h), available)
  // 宽高合成一次提交：早先「宽度先落、高度跳过等观察器补偿」，图片卡会停在
  // 「宽已变、高仍旧」的中间态；现在宽高（含卡片侧折算好的新高度）原子生效。
  const updates: CardUpdate[] = []
  if (width !== view.card.w) updates.push({ cardId: view.card.cardId, w: width })
  if (next !== view.card.h) updates.push({ cardId: view.card.cardId, h: next })
  if (updates.length) void page.commit(updates).catch(() => undefined)
}

/** ⤢ 菜单选比例：只约束卡片宽度（可视宽度 × 比例）；解除手动高度，高度交回贴合逻辑。 */
function onFitWidth(p: { cardId: string; ratio: number }) {
  const view = page.cards.find((b) => b.card.cardId === p.cardId)
  if (!view) return
  page.clearManualHeight(p.cardId)
  const width = Math.max(BLOCK_MIN_W, Math.round(fitCardMaxWidth(size.width, page.viewport.zoom) * p.ratio))
  if (width === view.card.w) return
  fullHeightFits.add(p.cardId)
  void page.commit([{ cardId: view.card.cardId, w: width }]).catch(() => undefined)
}

/** 自定义宽度（px）：只约束宽度；解除手动高度，高度交回贴合逻辑。 */
/** 自定义尺寸（px）：宽高都按用户输入固定，高度视为手动高度。 */
function onFitCustom(p: { cardId: string; width: number; height: number }) {
  const view = page.cards.find((b) => b.card.cardId === p.cardId)
  if (!view) return
  const width = Math.max(88, Math.round(p.width))
  const height = Math.max(BLOCK_MIN_H, Math.round(p.height))
  page.markManualHeight(p.cardId)
  const updates: CardUpdate[] = []
  if (width !== view.card.w) updates.push({ cardId: view.card.cardId, w: width })
  if (height !== view.card.h) updates.push({ cardId: view.card.cardId, h: height })
  if (updates.length) void page.commit(updates).catch(() => undefined)
}

// ---------------------------------------------------------------- 卡片预览（单击）

/** 单击卡片弹出的预览：完整内容按自然尺寸展示，形态对齐复习页「已开启卡片」。 */
const previewCard = ref<CardView | null>(null)

function onCardPreview(view: CardView) {
  previewCard.value = view
}

function closeCardPreview() {
  previewCard.value = null
}

// 预览图片缩放拖移：滚轮缩放 + 左键拖移作用于浮层内的图片，与复习页共用同一套交互
//（实现见 @thisnote/plugin-api 的 createPreviewImageInteractions）。
const { onPreviewWheel, onPreviewMouseDown, dispose: disposePreviewInteractions } = createPreviewImageInteractions({
  grabbingClass: 'preview-img-grabbing',
  stopWheelPropagation: true,
})

/**
 * 一键自动布局：打乱卡片顺序后逐个找「最靠上的空位」放下（优先填洞），
 * 放不下再往最下方追加；卡片保持原有宽高。
 */
function autoLayout() {
  const cards = page.cards.map((b) => b.card)
  if (cards.length === 0) return
  // 乱序：每次点都可能得到不同摆法，避免又排成整齐的行
  for (let i = cards.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[cards[i], cards[j]] = [cards[j], cards[i]]
  }

  const GAP = 24
  const zoom = Math.max(page.viewport.zoom, 0.01)
  const surfaceWidth = Math.max(320, Math.round(size.width / zoom) - 48)
  const left = Math.min(...cards.map((c) => c.x))
  const top = Math.min(...cards.map((c) => c.y))
  const placed: Array<{ x: number; y: number; w: number; h: number }> = []

  const overlaps = (x: number, y: number, w: number, h: number) => placed.some((r) => (
    x < r.x + r.w + GAP && x + w + GAP > r.x
    && y < r.y + r.h + GAP && y + h + GAP > r.y
  ))

  const updates = cards.map((card) => {
    // 候选位置：左上角 + 每个已放卡片的右侧 / 正下方
    const candidates: Array<{ x: number; y: number }> = [{ x: left, y: top }]
    for (const r of placed) {
      candidates.push({ x: r.x + r.w + GAP, y: r.y })
      candidates.push({ x: r.x, y: r.y + r.h + GAP })
    }
    let best: { x: number; y: number } | null = null
    for (const candidate of candidates) {
      if (candidate.x + card.w > left + surfaceWidth) continue
      if (overlaps(candidate.x, candidate.y, card.w, card.h)) continue
      if (!best || candidate.y < best.y - 0.5
        || (Math.abs(candidate.y - best.y) <= 0.5 && candidate.x < best.x)) {
        best = candidate
      }
    }
    if (!best) {
      const bottom = placed.reduce((max, r) => Math.max(max, r.y + r.h), top) + GAP
      best = { x: left, y: bottom }
    }
    placed.push({ x: best.x, y: best.y, w: card.w, h: card.h })
    return { cardId: card.cardId, x: best.x, y: best.y }
  })
  void page.commit(updates)
}

/** Double-click empty space: a card whose top-left corner is the clicked point. */
async function onDoubleClick(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('.card')) return
  const world = page.screenToWorld(localPoint(e))
  // The clicked point is the card's left/top edge, so the card lands where the user aimed, and
  // its width is sized to the visible page so a blank strip always remains.
  const created = await page.addBlock(
    { x: Math.round(world.x), y: Math.round(world.y) },
    { surfaceWidth: size.width / page.viewport.zoom, surfaceHeight: size.height / page.viewport.zoom },
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
      const created = await page.addBlockFromContent(markdown, at, imageSize, defaultCardWidthPx.value)
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
  const created = await page.addBlockFromContent(text, at, undefined, defaultCardWidthPx.value)
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
    if (previewCard.value) closeCardPreview()
    else if (page.editingCardId) void finishEditing()
    else page.clearSelection()
  } else if (e.key === 'F2' || e.key === 'Enter') {
    const single = page.singleSelected
    if (single) {
      e.preventDefault()
      closeCardPreview()
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

async function focusCard(cardId: string): Promise<boolean> {
  try {
    const view = await page.ensureCard(cardId)
    if (!view) {
      toast('目标卡片不存在或已被删除', 'error')
      return false
    }
    page.select([cardId])
    page.bringToFront(cardId)
    await nextTick()
    const zoom = page.viewport.zoom
    page.viewport = {
      zoom,
      x: view.card.x + view.card.w / 2 - size.width / (2 * zoom),
      y: view.card.y + view.card.h / 2 - size.height / (2 * zoom),
    }
    return true
  } catch (error) {
    toast(`定位卡片失败：${(error as Error).message}`, 'error')
    return false
  }
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

defineExpose({ size, beginEdit, finishEditing, focusCard })
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
        :manual-height="page.isManualHeight(view.card.cardId)"
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
        @preview="onCardPreview"
        @fit-width="onFitWidth"
        @fit-custom="onFitCustom"
        :fit-base-width="fitBaseWidth"
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
      <button class="btn sm" title="乱序摆放，优先填充空白" @click="autoLayout">自动布局</button>
      <button class="btn sm" title="重置 (Ctrl+0)" @click="page.resetView()">1:1</button>
      <button class="btn sm" :class="{ active: page.snapToGrid }" title="尺寸吸附" @click="page.toggleSnap()">吸附</button>
    </div>

    <NotePagePreview
      v-if="previewCard"
      :card="previewCard"
      :markdown-view="MarkdownView"
      :on-wheel="onPreviewWheel"
      :on-mouse-down="onPreviewMouseDown"
      @close="closeCardPreview"
    />
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
