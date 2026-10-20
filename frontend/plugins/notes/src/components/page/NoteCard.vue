<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { CardView } from '@/api/types'
import { activeCardPlugins, host, toast } from '@thisnote/plugin-api'
import { deleteUnreferencedImageAttachment, getClipboardImage, uploadImageAttachment } from './clipboardImage'
import { getClipboardMarkdown, getClipboardMarkdownFile, isClipboardCode, isMarkdown, type CardFormat } from './markdownFormat'
import CustomSizeDialog from './CustomSizeDialog.vue'
import NoteCardBody from './NoteCardBody.vue'
import NoteCardToolbar from './NoteCardToolbar.vue'

/** Markdown 渲染由宿主服务提供（markdown-it 只在宿主打一份）。 */
const MarkdownView = host().MarkdownView
import { useCardSizing } from './useCardSizing'

/**
 * One card on a note's page.
 *
 * A card is a fragment of its note. It is written in place (`textarea` over the card body), so
 * the interaction is exactly "click, type". What you type is written back to the note.
 *
 * A card has **no title field**: its label in the outline is the heading written in its own
 * content (`## …`), so there is only ever one place a card's name lives.
 *
 * Cards render Markdown by default and open the source editor in place while being edited.
 */
const props = defineProps<{
  view: CardView
  selected: boolean
  editing: boolean
  focused: boolean
  zIndex: number
  /** 视口折算出的「100% 宽度」，用于判断当前宽度落在哪个百分比档位。 */
  fitBaseWidth?: number
  /** 手动定过高度：图片卡不再用自动高度，按 card.h 渲染。 */
  manualHeight?: boolean
}>()

const emit = defineEmits<{
  (e: 'pointerdown', p: { event: PointerEvent; view: CardView; mode: 'move' | 'resize'; handle?: string }): void
  (e: 'request-edit', id: string): void
  (e: 'context', p: { event: MouseEvent; view: CardView; imageSrc?: string }): void
  (e: 'save', p: { cardId: string; contentMd: string }): void
  (e: 'end-edit'): void
  (e: 'toggle-collapse', id: string): void
  (e: 'focus', id: string): void
  (e: 'fit-height', p: { cardId: string; h: number; w?: number; fitImage?: boolean; force?: boolean }): void
  (e: 'preview', view: CardView): void
  (e: 'fit-width', p: { cardId: string; ratio: number }): void
  (e: 'fit-custom', p: { cardId: string; width: number; height: number }): void
}>()

const draft = ref('')
const pastingImage = ref(false)
const pastingMarkdownFile = ref(false)
const autoFitImageWidth = ref(false)
const fitPastedImage = ref(false)
let blurAfterImagePaste = false
const pendingImageAttachments = new Set<string>()
const loadedFor = ref<string | null>(null)
const bodyRef = ref<InstanceType<typeof NoteCardBody> | null>(null)
/** Off-screen copy of the draft, used only to measure how tall the text really is. */
const measure = ref<HTMLElement | null>(null)
/** 卡片根元素：用「渲染宽 / 逻辑宽」折算画布缩放，供行宽测量换算回世界像素。 */
const rootEl = ref<HTMLElement | null>(null)

const card = computed(() => props.view.card)
const content = computed(() => props.view.contentMd ?? '')
/** 内容只有一张图片：图片按卡片内容宽度等比伸缩（初始尺寸即原图 1:1）。 */
const IMAGE_ONLY = /^!\[[^\]]*\]\(.+\)$/
const isImageOnly = computed(() => IMAGE_ONLY.test(content.value.trim()))
const sourceVisible = computed(() => !card.value.collapsed && props.editing)
const formatMode = ref<CardFormat>('auto')
const detectedFormat = computed(() => (isMarkdown(props.editing ? draft.value : content.value) ? 'markdown' : 'text'))
const effectiveFormat = computed(() => formatMode.value === 'auto' ? detectedFormat.value : formatMode.value)
const detectedFormatLabel = computed(() => detectedFormat.value === 'markdown' ? 'Markdown' : '普通文本')
const {
  activePreset,
  fitHeight,
  autoFit,
  chooseFitWidth,
  customSizeOpen,
  customWidthDraft,
  customHeightDraft,
  chooseCustomSize,
  applyCustomSize,
} = useCardSizing(
  props,
  emit,
  card,
  content,
  isImageOnly,
  () => bodyRef.value?.readBodyElement() ?? null,
  measure,
  () => bodyRef.value?.textareaElement() ?? null,
  rootEl,
  autoFitImageWidth,
  fitPastedImage,
)

function readFormatMode(cardId: string): CardFormat {
  try {
    const value = localStorage.getItem(`thisnote.notes.card-format.${cardId}`)
    return value === 'markdown' || value === 'text' ? value : 'auto'
  } catch {
    return 'auto'
  }
}

function selectFormat(mode: CardFormat) {
  formatMode.value = mode
  try {
    localStorage.setItem(`thisnote.notes.card-format.${card.value.cardId}`, mode)
  } catch {
    // Keep the selection for this card until it is closed, even when browser storage is unavailable.
  }
}

function focusFormatControl() {
  emit('focus', card.value.cardId)
}

watch(
  () => [props.editing, card.value.cardId] as const,
  async ([editing, id]) => {
    if (!editing) {
      loadedFor.value = null
      return
    }
    if (loadedFor.value === id) return
    draft.value = content.value
    loadedFor.value = id
    await nextTick()
    bodyRef.value?.focus()
    const editor = bodyRef.value?.textareaElement()
    const len = editor?.value.length ?? 0
    editor?.setSelectionRange(len, len)
  },
  { immediate: true },
)

watch(
  () => card.value.cardId,
  (id) => {
    loadedFor.value = null
    draft.value = ''
    formatMode.value = readFormatMode(id)
  },
  { immediate: true },
)

/**
 * 卡片内的指针按下：按**指针坐标**判定意图，而不是靠边缘覆盖层的命中 ——
 * 细条热区在绝对定位 + 画布缩放下会被卡身抢走命中（实测 right-1px 处属于卡身）。
 *
 * <p>规则：左/右/下边框及其组合 = 拉伸（角落双向），其余（含上边框与卡身）= 移动。
 * 右下角优先于单边，所以角落能斜向拉伸。
 */
function onBodyPointerDown(e: PointerEvent) {
  pointerDownAt = { x: e.clientX, y: e.clientY }
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  const EDGE = 6
  const nearLeft = e.clientX - r.left <= EDGE
  const nearRight = r.right - e.clientX <= EDGE
  const nearTop = e.clientY - r.top <= EDGE
  const nearBottom = r.bottom - e.clientY <= EDGE
  if (nearTop && (nearLeft || nearRight)) {
    emit('pointerdown', { event: e, view: props.view, mode: 'resize', handle: nearLeft ? 'nw' : 'ne' })
    return
  }
  if (nearBottom && (nearLeft || nearRight)) {
    emit('pointerdown', { event: e, view: props.view, mode: 'resize', handle: nearLeft ? 'sw' : 'se' })
    return
  }
  if (nearLeft || nearRight) {
    emit('pointerdown', { event: e, view: props.view, mode: 'resize', handle: nearLeft ? 'w' : 'e' })
    return
  }
  if (nearBottom && !nearTop) {
    emit('pointerdown', { event: e, view: props.view, mode: 'resize', handle: 's' })
    return
  }
  emit('pointerdown', { event: e, view: props.view, mode: 'move' })
}

// ---------------------------------------------------------------- 单击预览 / 双击编辑

/**
 * 单击预览、双击编辑：单击延迟 250ms 才发预览，双击先取消它再进编辑 ——
 * 双击的第一次 click 不会抢先弹出预览浮层；拖卡后的 click 按位移判定不算点击。
 */
let pointerDownAt: { x: number; y: number } | null = null
let previewTimer: number | null = null

function cancelPendingPreview() {
  if (previewTimer === null) return
  window.clearTimeout(previewTimer)
  previewTimer = null
}

function onBodyClick(e: MouseEvent) {
  emit('focus', card.value.cardId)
  if (props.editing || card.value.collapsed) return
  const target = e.target as HTMLElement
  if (target.closest('.card-tools, .format-control, .fit-tools')) return
  const down = pointerDownAt
  if (down && Math.abs(e.clientX - down.x) + Math.abs(e.clientY - down.y) > 3) return
  if (previewTimer !== null) return
  previewTimer = window.setTimeout(() => {
    previewTimer = null
    emit('preview', props.view)
  }, 250)
}

function onBodyDblclick() {
  cancelPendingPreview()
  emit('request-edit', card.value.cardId)
}

function commit() {
  // Save what is in the DOM right now: an empty card must be recognisable as empty.
  const editor = bodyRef.value?.textareaElement()
  const text = editor ? editor.value : draft.value
  draft.value = text
  for (const markdown of pendingImageAttachments) {
    if (!text.includes(markdown)) void deleteUnreferencedImageAttachment(markdown).catch(() => undefined)
    pendingImageAttachments.delete(markdown)
  }
  emit('save', { cardId: card.value.cardId, contentMd: text })
}

async function onPaste(e: ClipboardEvent) {
  const image = getClipboardImage(e.clipboardData)
  if (!image) {
    const markdownFile = getClipboardMarkdownFile(e.clipboardData)
    const clipboardCode = isClipboardCode(e.clipboardData)
    const hasHtml = Boolean(e.clipboardData?.getData('text/html').trim())
    if (!markdownFile && !clipboardCode && !hasHtml) return
    e.preventDefault()
    const input = e.currentTarget as HTMLTextAreaElement
    const start = input.selectionStart
    const end = input.selectionEnd
    const cardId = card.value.cardId
    pastingMarkdownFile.value = Boolean(markdownFile)
    try {
      const markdown = await getClipboardMarkdown(e.clipboardData)
      if (!markdown || card.value.cardId !== cardId || !props.editing
        || bodyRef.value?.textareaElement() !== input) return
      input.setRangeText(markdown, Math.min(start, input.value.length), Math.min(end, input.value.length), 'end')
      draft.value = input.value
      if (markdownFile) selectFormat('markdown')
      else if (clipboardCode && formatMode.value === 'auto') selectFormat('text')
      if (/!\[[^\]]*\]\([^)]+\)/.test(markdown)) {
        autoFitImageWidth.value = true
        fitPastedImage.value = true
      }
      await nextTick()
      fitHeight()
    } catch (error) {
      toast(`粘贴 Markdown 失败：${error instanceof Error ? error.message : '无法读取文件'}`, 'error')
    } finally {
      pastingMarkdownFile.value = false
    }
    return
  }
  e.preventDefault()
  if (pastingImage.value) return

  const input = e.currentTarget as HTMLTextAreaElement
  const start = input.selectionStart
  const end = input.selectionEnd
  const cardId = card.value.cardId
  pastingImage.value = true
  autoFitImageWidth.value = true
  fitPastedImage.value = true
  try {
    const markdown = await uploadImageAttachment(image)
    if (card.value.cardId !== cardId || !props.editing || bodyRef.value?.textareaElement() !== input) {
      await deleteUnreferencedImageAttachment(markdown)
      return
    }

    pendingImageAttachments.add(markdown)
    const text = input.value
    const from = Math.min(start, text.length)
    const to = Math.min(Math.max(end, from), text.length)
    draft.value = text.slice(0, from) + markdown + text.slice(to)
    selectFormat('markdown')
    await nextTick()
    input.setSelectionRange(from + markdown.length, from + markdown.length)
    fitHeight()
    if (blurAfterImagePaste) {
      blurAfterImagePaste = false
      commit()
    }
  } catch (error) {
    autoFitImageWidth.value = false
    fitPastedImage.value = false
    toast(`粘贴图片失败：${error instanceof Error ? error.message : '上传图片失败'}`, 'error')
    if (blurAfterImagePaste) {
      blurAfterImagePaste = false
      commit()
    }
  } finally {
    pastingImage.value = false
  }
}

function onBlur() {
  if (pastingImage.value) {
    blurAfterImagePaste = true
    return
  }
  commit()
}

function onContextMenu(e: MouseEvent) {
  const target = e.target
  const image = target instanceof Element ? target.closest('img') : null
  const imageSrc = image instanceof HTMLImageElement ? image.currentSrc || image.src : undefined
  emit('context', { event: e, view: props.view, imageSrc })
}

function onKeydown(e: KeyboardEvent) {
  if (pastingImage.value) {
    e.preventDefault()
    e.stopPropagation()
    return
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    for (const markdown of pendingImageAttachments) {
      void deleteUnreferencedImageAttachment(markdown).catch(() => undefined)
    }
    pendingImageAttachments.clear()
    emit('end-edit')
    return
  }
  if (e.key.toLowerCase() === 's' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    commit()
    return
  }
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    commit()
  }
  // Card-local shortcuts must not reach the page (Ctrl+A, Delete…).
  e.stopPropagation()
}

onBeforeUnmount(() => {
  cancelPendingPreview()
  for (const markdown of pendingImageAttachments) {
    void deleteUnreferencedImageAttachment(markdown).catch(() => undefined)
  }
  pendingImageAttachments.clear()
})
</script>

<template>
  <article
    ref="rootEl"
    class="card"
    :data-card-id="card.cardId"
    :class="{ selected, editing, collapsed: card.collapsed, focused, 'image-only': isImageOnly, 'manual-size': manualHeight }"
    :style="{
      transform: `translate3d(${card.x}px, ${card.y}px, 0)`,
      width: `${card.w}px`,
      height: card.collapsed ? '26px' : isImageOnly && !editing && !manualHeight ? 'auto' : `${card.h}px`,
      zIndex: props.zIndex,
    }"
    @pointerdown="onBodyPointerDown"
    @dblclick.stop="onBodyDblclick"
    @contextmenu.prevent.stop="onContextMenu"
    @click="onBodyClick"
  >
    <NoteCardToolbar
      :card-id="card.cardId"
      :collapsed="card.collapsed"
      :format-mode="formatMode"
      :detected-format-label="detectedFormatLabel"
      :active-preset="activePreset"
      :plugins="activeCardPlugins()"
      @toggle-collapse="emit('toggle-collapse', card.cardId)"
      @select-format="selectFormat"
      @focus-format="focusFormatControl"
      @choose-fit-width="chooseFitWidth"
      @choose-custom-size="chooseCustomSize"
    />

    <NoteCardBody
      ref="bodyRef"
      v-model:draft="draft"
      :collapsed="card.collapsed"
      :editing="editing"
      :content="content"
      :effective-format="effectiveFormat"
      :pasting-image="pastingImage"
      :pasting-markdown-file="pastingMarkdownFile"
      :markdown-view="MarkdownView"
      @keydown="onKeydown"
      @paste="onPaste"
      @blur="onBlur"
      @input="fitHeight"
    />

    <!-- Off-screen copy of the draft lets "grow to fit" measure text instead of the textarea's
         current card-sized box. -->
    <div
      v-if="sourceVisible"
      ref="measure"
      class="body measure measure-full"
      aria-hidden="true"
    >{{ draft }}</div>

    <!-- 边缘光标提示条：只负责 cursor（判定在 onBodyPointerDown 里按坐标做）。
         左/右/下=拉伸，上=移动；角落的光标由相邻边给出，行为上是双向拉伸。 -->
    <template v-if="!card.collapsed">
      <span
        v-for="pos in ['n', 'w', 'e', 's']"
        :key="'edge-' + pos"
        class="edge"
        :class="pos"
      ></span>
      <span
        v-for="pos in ['nw', 'ne', 'sw', 'se']"
        :key="'corner-' + pos"
        class="edge corner"
        :class="pos"
      ></span>
    </template>

    <CustomSizeDialog
      :open="customSizeOpen"
      :width="customWidthDraft"
      :height="customHeightDraft"
      :title="isImageOnly ? '图片尺寸' : '卡片尺寸'"
      @update:width="customWidthDraft = $event"
      @update:height="customHeightDraft = $event"
      @cancel="customSizeOpen = false"
      @confirm="applyCustomSize"
    />
  </article>
</template>

<style scoped>
.card {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  border-radius: 10px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  /* `contain` scopes layout/paint to the card. No `will-change`: measured slower and it
     promotes every card to its own GPU layer. */
  contain: layout paint style;
  user-select: none;
  transition: border-color 0.12s, box-shadow 0.12s;
}
.card.focused,
.card.selected {
  border-color: var(--accent);
}
.card.selected {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 30%, transparent), var(--shadow);
}
.card.editing {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent), var(--shadow-lg);
}
.card.collapsed {
  height: 40px !important;
}
.card:hover :deep(.collapse),
.card.selected :deep(.collapse),
.card.focused :deep(.collapse),
.card.editing :deep(.collapse),
.card.collapsed :deep(.collapse),
.card:hover :deep(.auto-fit),
.card.selected :deep(.auto-fit),
.card.focused :deep(.auto-fit),
.card.editing :deep(.auto-fit),
.card:hover :deep(.fit-pct),
.card.selected :deep(.fit-pct),
.card.focused :deep(.fit-pct),
.card.editing :deep(.fit-pct) {
  opacity: 1;
}

/* 图片卡：内容区宽度即图片宽度，随卡片宽度等比伸缩（1:1 初始）；高度由内容撑开，永不出现滚动条 */
.card.image-only :deep(.body.read .md-preview img) {
  width: 100%;
  max-width: 100%;
  max-height: none;
  height: auto;
}
.card.image-only:not(.manual-size) :deep(.body.read) {
  overflow: hidden;
}

/* Measurement twin of the editing textarea: same box and type metrics, but laid out off-screen
   so it reports the height the text needs instead of the height the card happens to have. */
.body.measure {
  flex: 1;
  min-height: 0;
  min-width: 0;
  margin: 0;
  padding: 10px 14px 12px;
  font-size: 15px;
  line-height: 1.85;
  border-radius: 0;
  position: fixed;
  left: -10000px;
  top: 0;
  visibility: hidden;
  pointer-events: none;
  white-space: pre-wrap;
  word-break: break-word;
  overflow: hidden;
  padding-top: 0;
  padding-bottom: 0;
  font-family: var(--font-ui);
  color: var(--c-text);
}
/* The twin matches the editing textarea's full width. */
.measure-full {
  width: 100%;
}

/* --- edge cursor strips: 只在边缘给出光标提示，判定在 onBodyPointerDown 里按坐标做 --- */
.edge {
  position: absolute;
  z-index: 3;
}
.edge.n { left: 0; right: 0; top: -3px; height: 6px; cursor: move; }
.edge.w { top: 0; bottom: 0; left: -3px; width: 6px; cursor: ew-resize; }
.edge.e { top: 0; bottom: 0; right: -3px; width: 6px; cursor: ew-resize; }
.edge.s { left: 0; right: 0; bottom: -3px; height: 6px; cursor: ns-resize; }
.edge.nw { top: 0; left: 0; width: 6px; height: 6px; cursor: nwse-resize; }
.edge.ne { top: 0; right: 0; width: 6px; height: 6px; cursor: nesw-resize; }
.edge.sw { bottom: 0; left: 0; width: 6px; height: 6px; cursor: nesw-resize; }
.edge.se { bottom: 0; right: 0; width: 6px; height: 6px; cursor: nwse-resize; }
</style>
