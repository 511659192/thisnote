<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CardView } from '@/api/types'
import { activeCardPlugins, host, toast } from '@thisnode/plugin-api'
import { deleteUnreferencedImageAttachment, getClipboardImage, uploadImageAttachment } from './clipboardImage'
import { getClipboardMarkdown, getClipboardMarkdownFile, isClipboardCode, isMarkdown, type CardFormat } from './markdownFormat'
import CustomWidthDialog from './CustomWidthDialog.vue'

/** Markdown 渲染由宿主服务提供（markdown-it 只在宿主打一份）。 */
const MarkdownView = host().MarkdownView
import { BLOCK_MIN_H } from '@/stores/notePage'

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
  (e: 'fit-custom', p: { cardId: string; width: number }): void
}>()

const draft = ref('')
const pastingImage = ref(false)
const pastingMarkdownFile = ref(false)
const autoFitImageWidth = ref(false)
const fitPastedImage = ref(false)
let blurAfterImagePaste = false
const pendingImageAttachments = new Set<string>()
const loadedFor = ref<string | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
/** Off-screen copy of the draft, used only to measure how tall the text really is. */
const measure = ref<HTMLElement | null>(null)
/** 预览态的渲染容器：量它里面内容根节点的自然高度。 */
const readBody = ref<HTMLElement | null>(null)
/** 卡片根元素：用「渲染宽 / 逻辑宽」折算画布缩放，供行宽测量换算回世界像素。 */
const rootEl = ref<HTMLElement | null>(null)

const card = computed(() => props.view.card)
const content = computed(() => props.view.contentMd ?? '')
/** 图片卡 1:1 宽度（原图宽 + 内边距），用于判断当前是否停在 1:1。 */
const imageFitWidth = ref<number | null>(null)

/** 按当前宽度反推选中的档位：命中 1:1/百分比则高亮对应项，否则高亮「自定义」。 */
const activePreset = computed<string>(() => {
  const width = Math.round(card.value.w)
  if (imageFitWidth.value && Math.abs(width - imageFitWidth.value) <= 2) return '1:1'
  const base = props.fitBaseWidth
  if (base) {
    for (const ratio of [0.25, 0.5, 0.75, 1]) {
      if (Math.abs(width - Math.round(base * ratio)) <= 2) return `${Math.round(ratio * 100)}%`
    }
  }
  return 'custom'
})

// 图片异步加载：拿到原图宽度后，1:1 档位的高亮才能与当前宽度对上
onMounted(() => {
  void nextTick(() => {
    const images = Array.from(readBody.value?.querySelectorAll('img') ?? [])
    const sync = () => {
      const loaded = images.filter((image) => image.naturalWidth > 0)
      imageFitWidth.value = loaded.length
        ? Math.max(...loaded.map((image) => image.naturalWidth)) + 30
        : null
    }
    sync()
    for (const image of images) image.addEventListener('load', sync, { once: true })
  })
})
/** 内容只有一张图片：图片按卡片内容宽度等比伸缩（初始尺寸即原图 1:1）。 */
const IMAGE_ONLY = /^!\[[^\]]*\]\(.+\)$/
const isImageOnly = computed(() => IMAGE_ONLY.test(content.value.trim()))
const sourceVisible = computed(() => !card.value.collapsed && props.editing)
const formatMode = ref<CardFormat>('auto')
const detectedFormat = computed(() => (isMarkdown(props.editing ? draft.value : content.value) ? 'markdown' : 'text'))
const effectiveFormat = computed(() => formatMode.value === 'auto' ? detectedFormat.value : formatMode.value)
const detectedFormatLabel = computed(() => detectedFormat.value === 'markdown' ? 'Markdown' : '普通文本')

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
    textarea.value?.focus()
    const len = textarea.value?.value.length ?? 0
    textarea.value?.setSelectionRange(len, len)
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
  const text = textarea.value ? textarea.value.value : draft.value
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
      if (!markdown || card.value.cardId !== cardId || !props.editing || textarea.value !== input) return
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
    if (card.value.cardId !== cardId || !props.editing || textarea.value !== input) {
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

/**
 * 量预览内容的 max-content 宽度：克隆到屏外按「不换行一行放下」布局取宽。
 * 不在当前渲染上量行宽——按当前换行量、缩完再换行会死循环，把难看的断行锁死。
 */
function measureMaxContentWidth(): number {
  // 编辑态 readBody 不渲染：量屏外 twin（与 textarea 同字体宽度），同样是「最长一行」的宽度。
  const body = readBody.value ?? measure.value
  if (!body) return 0
  const clone = body.cloneNode(true) as HTMLElement
  clone.style.cssText += ';position:fixed;left:-10000px;top:0;width:max-content;max-width:none;overflow:visible;'
  document.body.appendChild(clone)
  const width = Math.ceil(clone.scrollWidth)
  clone.remove()
  return width
}

/**
 * 让卡片高度贴合内容，双向：长文长高、删改后收缩 —— 卡片下部不再留大片空白。
 * 展示上限（长文到浏览器底缘、超出滚动）由页面在 onFitHeight 里按视口折算。
 *
 * <p>编辑态量**屏外 twin**：textarea 会撑满卡片，量它自己等于"自己量自己"，只会越量越高。
 * 预览态量内容根节点的自然高度再补容器 padding——滚动容器的 scrollHeight 会被自身
 * client 高度兜底，量它永远缩不回去。8px 迟滞防止一次输入恰好跨过阈值时
 * 反复拉扯；88px 下限与页面的 BLOCK_MIN_H 一致。宽度只扩不缩：内容超出右缘才贴过去。
 */
function fitHeight(force = false) {
  if (card.value.collapsed) return
  const el = contentEl()
  if (!el) return
  // 有了真实信号才挂观察器（见 armFitObserver）：避免"每次打开笔记都贴合一次"。
  armFitObserver()

  const images = Array.from(el.querySelectorAll('img'))
  const pendingImages = images.filter((image) => !image.complete)
  if (pendingImages.length) {
    // MarkdownView 的图片异步加载：加载完成前量的高度不含图片。
    // 不消费一次性贴合信号，等最后一张就位（或确认失败）后带 force 重贴。
    for (const image of pendingImages) {
      if (image.dataset.fitRefitArmed) continue
      image.dataset.fitRefitArmed = '1'
      image.addEventListener('load', () => fitHeight(true), { once: true })
      image.addEventListener('error', () => fitHeight(true), { once: true })
    }
    return
  }
  const loadedImages = images.filter((image) => image.naturalWidth > 0)
  imageFitWidth.value = loadedImages.length
    ? Math.max(...loadedImages.map((image) => image.naturalWidth)) + 30
    : null
  const waitingForAttachmentImage = autoFitImageWidth.value && images.some(
    (image) => image.dataset.thisnodeAttachment && image.getAttribute('src') === 'about:blank',
  )

  // 高度基准：量**内容根节点的自然高度**——.body.read 是滚动容器，scrollHeight 会被
  // 自身 client 高度兜底，直接量它高度永远缩不回去（贴合后底部仍大片空白的原因）。
  // 内容自然高度 + 容器真实竖向 padding（预览态 30/12，编辑态按 textarea 计算样式）+ 边框。
  let needed: number
  if (props.editing && textarea.value) {
    const boxStyle = getComputedStyle(textarea.value)
    const verticalPadding = parseFloat(boxStyle.paddingTop) + parseFloat(boxStyle.paddingBottom)
    needed = Math.ceil(el.offsetHeight + verticalPadding) + 2
  } else if (readBody.value) {
    const bodyStyle = getComputedStyle(readBody.value)
    const verticalPadding = parseFloat(bodyStyle.paddingTop) + parseFloat(bodyStyle.paddingBottom)
    needed = Math.ceil(el.offsetHeight + verticalPadding) + 2
  } else {
    return
  }
  let target = Math.max(BLOCK_MIN_H, needed)

  // 宽度贴合只在显式触发时做（⤢ / 图片粘贴）：日常输入若跟随内容调宽，卡片会随键击抖动。
  // 只扩不缩的 scrollWidth 量不出换行文本的最长行，这里改量**最长可见行**的实际渲染宽度，
  // 双向贴合：短行多的卡收窄贴右边框，长行/代码/图片的卡扩到放得下。
  let width: number | undefined
  if (force || autoFitImageWidth.value) {
    const desiredWidths: number[] = []
    // 图片按原图宽度（卡片宽 = 图片宽 + 30，内容区与图片 1:1）
    const imageWidth = loadedImages.length
      ? Math.max(...loadedImages.map((image) => image.naturalWidth)) + 30
      : 0
    if (imageWidth > 0) desiredWidths.push(imageWidth)
    const maxContentWidth = measureMaxContentWidth()
    // 克隆体已含 .body.read 左右 28px 内边距，再补卡片左右边框 2px
    if (maxContentWidth > 0) desiredWidths.push(maxContentWidth + 2)
    if (desiredWidths.length) {
      if (!waitingForAttachmentImage) autoFitImageWidth.value = false
      // 纯图片卡以图片原始宽度为准，不受文本量出来的宽度干扰
      const desired = isImageOnly.value && imageWidth > 0
        ? imageWidth
        : Math.max(...desiredWidths)
      // 4px 容差避免分数像素来回拉扯；上限由页面侧 maxCardWidth 钳制
      if (Math.abs(desired - card.value.w) > 4) width = desired
    }
  }

  // 宽度变化时图片渲染高度随 max-width 缩放：上面量的 scrollHeight 反映旧宽度，
  // 按各图片纵横比把目标高度折算到新宽度，避免「宽度贴合了、高度还是旧宽度的高度」。
  if (width !== undefined && rootEl.value) {
    const zoom = rootEl.value.getBoundingClientRect().width / card.value.w
    if (Number.isFinite(zoom) && zoom > 0) {
      const innerOld = Math.max(1, card.value.w - 30)
      const innerNew = Math.max(1, width - 30)
      let delta = 0
      for (const image of loadedImages) {
        const oldHeight = image.getBoundingClientRect().height / zoom
        const oldWidth = Math.min(image.naturalWidth, innerOld)
        if (oldWidth <= 0 || oldHeight <= 0) continue
        delta += oldHeight * (Math.min(image.naturalWidth, innerNew) / oldWidth) - oldHeight
      }
      target = Math.max(BLOCK_MIN_H, Math.round(target + delta))
    }
  }

  if (width !== undefined || Math.abs(target - card.value.h) > 8 || force) {
    emit('fit-height', { cardId: card.value.cardId, h: target, w: width, fitImage: fitPastedImage.value, force })
  }
  if (!props.editing && loadedImages.length) fitPastedImage.value = false
}

/** 「自动调整」：解除手动高度锁定，并立即按当前内容贴合。 */
function autoFit() {
  autoFitImageWidth.value = true
  fitPastedImage.value = true
  fitHeight(true)
}

/** ⤢ 排固定选项：自动贴合，或把卡片宽度设为可视宽度的一定比例（比例由页面侧折算提交）。 */
function chooseFitWidth(ratio: number | null) {
  if (ratio === null) {
    autoFit()
    return
  }
  emit('fit-width', { cardId: card.value.cardId, ratio })
}

/** 自定义宽度（px）：只约束宽度，高度仍由贴合逻辑负责。 */
const customWidthOpen = ref(false)
const customWidthDraft = ref('')

function chooseCustomWidth() {
  // 纯图片卡默认填图片原始宽度；其它卡片退回当前卡片宽度
  const imageWidth = isImageOnly.value && imageFitWidth.value ? imageFitWidth.value - 30 : 0
  customWidthDraft.value = String(Math.round(imageWidth > 0 ? imageWidth : card.value.w))
  customWidthOpen.value = true
}

function applyCustomWidth() {
  const value = Number(customWidthDraft.value.trim())
  if (!Number.isFinite(value) || value < 88) return
  customWidthOpen.value = false
  // 图片卡的输入是图片宽度，卡片宽 = 图片宽 + 内容区左右内边距
  emit('fit-custom', { cardId: card.value.cardId, width: isImageOnly.value ? value + 30 : value })
}

/** 当前要量的内容节点：编辑态是屏外 twin，预览态是渲染出来的内容根节点。 */
function contentEl(): HTMLElement | null {
  return props.editing
    ? measure.value
    : ((readBody.value?.firstElementChild as HTMLElement | null) ?? null)
}

/**
 * 观察器**只在出现真正的贴合信号之后**才挂上。
 *
 * <p>ResizeObserver 一挂上就会先回调一次；若在挂载时就挂，等于「每打开/切回一次笔记都强制
 * 贴合一次」—— 那会把用户自己调好的高度覆盖成按视口封顶的值（切走再切回，高度弹回浏览器长度）。
 * 只在内容、编辑模式或卡片宽度变化时触发首次贴合；观察器挂上后，图片加载、markdown 重排等尺寸
 * 变化才会继续触发贴合。
 */
let fitObserver: ResizeObserver | null = null
let observedEl: HTMLElement | null = null
function armFitObserver() {
  const el = contentEl()
  if (!el || el === observedEl) return
  observedEl = el
  if (!fitObserver) fitObserver = new ResizeObserver(() => fitHeight())
  fitObserver.disconnect()
  fitObserver.observe(el)
}
watch([() => props.editing, () => card.value.w], () => {
  void nextTick(() => fitHeight())
})
watch(() => content.value, () => void nextTick(() => fitHeight()))
onBeforeUnmount(() => {
  cancelPendingPreview()
  for (const markdown of pendingImageAttachments) {
    void deleteUnreferencedImageAttachment(markdown).catch(() => undefined)
  }
  pendingImageAttachments.clear()
  fitObserver?.disconnect()
  observedEl = null
})
</script>

<template>
  <article
    ref="rootEl"
    class="card"
    :data-card-id="card.cardId"
    :class="{ selected, editing, collapsed: card.collapsed, focused, 'image-only': isImageOnly }"
    :style="{
      transform: `translate3d(${card.x}px, ${card.y}px, 0)`,
      width: `${card.w}px`,
      height: card.collapsed ? '26px' : isImageOnly && !editing ? 'auto' : `${card.h}px`,
      zIndex: props.zIndex,
    }"
    @pointerdown="onBodyPointerDown"
    @dblclick.stop="onBodyDblclick"
    @contextmenu.prevent.stop="onContextMenu"
    @click="onBodyClick"
  >
    <!-- Card tools are overlaid in the top-right corner so they cost no layout space. -->
    <div class="card-tools">
      <template v-if="!card.collapsed">
        <component
          :is="plugin.cardComponent"
          v-for="plugin in activeCardPlugins()"
          :key="plugin.id"
          :card-id="card.cardId"
        />
      </template>
      <button
        class="collapse"
        :class="{ active: card.collapsed }"
        :title="card.collapsed ? '展开卡片' : '折叠卡片'"
        @pointerdown.stop
        @click.stop="emit('toggle-collapse', card.cardId)"
      >
        {{ card.collapsed ? '▸' : '▾' }}
      </button>
    </div>

    <!-- body: preview by default; editing opens the Markdown source in place. -->
    <textarea
      v-if="!card.collapsed && editing"
      ref="textarea"
      v-model="draft"
      class="body edit"
      :readonly="pastingImage || pastingMarkdownFile"
      spellcheck="false"
      placeholder="在这里写…（Ctrl+Enter 保存，Esc 退出）"
      @pointerdown.stop
      @dblclick.stop
      @keydown="onKeydown"
      @paste="onPaste"
      @blur="onBlur"
      @input="fitHeight"
    ></textarea>

    <div v-else-if="!card.collapsed" ref="readBody" class="body read">
      <component :is="MarkdownView"
        v-if="content && effectiveFormat === 'markdown'"
        :content="content"
      />
      <div v-else-if="content" class="preview plain-text">{{ content }}</div>
      <div v-else class="preview empty-hint">点击写点什么</div>
    </div>

    <div class="format-control" @pointerdown.stop @click.stop="focusFormatControl">
      <button
        class="format-btn"
        :class="{ active: formatMode === 'auto' }"
        type="button"
        :title="'自动判定（当前：' + detectedFormatLabel + '）'"
        aria-label="格式：自动判定"
        @click="selectFormat('auto')"
      >A</button>
      <button
        class="format-btn"
        :class="{ active: formatMode === 'markdown' }"
        type="button"
        title="Markdown 格式"
        aria-label="格式：Markdown"
        @click="selectFormat('markdown')"
      >M</button>
      <button
        class="format-btn"
        :class="{ active: formatMode === 'text' }"
        type="button"
        title="普通文本格式"
        aria-label="格式：普通文本"
        @click="selectFormat('text')"
      >T</button>
    </div>

    <div v-if="!card.collapsed" class="fit-tools" @pointerdown.stop @click.stop>
      <button
        class="auto-fit"
        :class="{ active: activePreset === '1:1' }"
        title="1:1：宽度贴合内容"
        @click.stop="chooseFitWidth(null)"
      >1:1</button>
      <button class="fit-pct" :class="{ active: activePreset === '25%' }" title="卡片宽度：可视宽度的 25%" @click.stop="chooseFitWidth(0.25)">25%</button>
      <button class="fit-pct" :class="{ active: activePreset === '50%' }" title="卡片宽度：可视宽度的 50%" @click.stop="chooseFitWidth(0.5)">50%</button>
      <button class="fit-pct" :class="{ active: activePreset === '75%' }" title="卡片宽度：可视宽度的 75%" @click.stop="chooseFitWidth(0.75)">75%</button>
      <button class="fit-pct" :class="{ active: activePreset === '100%' }" title="卡片宽度：顶满可视宽度（留出页面留白）" @click.stop="chooseFitWidth(1)">100%</button>
      <button class="fit-pct" :class="{ active: activePreset === 'custom' }" title="自定义宽度（px）" @click.stop="chooseCustomWidth">自定义</button>
    </div>

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

    <CustomWidthDialog
      v-model="customWidthDraft"
      :open="customWidthOpen"
      :title="isImageOnly ? '图片宽度' : '卡片宽度'"
      @cancel="customWidthOpen = false"
      @confirm="applyCustomWidth"
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

.format-control {
  position: absolute;
  top: 4px;
  left: 6px;
  z-index: 4;
  display: flex;
  gap: 2px;
}
/* 宽度贴合工具（1:1 / 百分比）：放在左侧格式按钮之后，只控制宽度，高度由贴合逻辑负责 */
.fit-tools {
  position: absolute;
  top: 4px;
  left: 76px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 2px;
}
.format-btn {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}
.format-btn:hover,
.format-btn.active {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

/* --- card tools: an overlay row in the top-right corner ---
    The container itself never fades: plugin chips stay visible on an enabled card even at rest. */
.card-tools {
  position: absolute;
  top: 4px;
  right: 6px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 2px;
}
.collapse {
  /* Hidden by default; the button fades in on hover and while selected or being written in. */
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .collapse,
.card.selected .collapse,
.card.focused .collapse,
.card.editing .collapse,
.card.collapsed .collapse {
  opacity: 1;
}

/* The collapse control shows while expanded too, otherwise there would be no way to fold a
   card back once the strip that used to hold it is gone. */
.collapse {
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  line-height: 1;
}
.collapse:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.collapse.active {
  color: var(--accent);
}
/* 「自动调整」与宽度百分比、折叠按钮同排同款，悬停显形 */
.auto-fit {
  height: 20px;
  padding: 0 4px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .auto-fit,
.card.selected .auto-fit,
.card.focused .auto-fit,
.card.editing .auto-fit {
  opacity: 1;
}
.auto-fit:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.fit-pct {
  height: 20px;
  padding: 0 5px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .fit-pct,
.card.selected .fit-pct,
.card.focused .fit-pct,
.card.editing .fit-pct {
  opacity: 1;
}
.fit-pct:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.auto-fit.active,
.fit-pct.active {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

/* --- body: the writing surface --- */
.body {
  flex: 1;
  min-height: 0;
  /* Without this a textarea's intrinsic width can push a flex item wider than its card. */
  min-width: 0;
  margin: 0;
  /* Kept tight at the top: the tools are a hidden overlay, not a band, so the content should
     start close to the card's edge. */
  padding: 10px 14px 12px;
  font-size: 15px;
  line-height: 1.85;
  border-radius: 0;
}
.body.read {
  overflow-y: auto;
  overflow-x: hidden;
  user-select: text;
  /* The read view is the card's grab area now that the top strip is gone. */
  cursor: move;
  padding-top: 30px;
}
.body.read :deep(.md-preview img) {
  max-height: none;
}
/* 图片卡：内容区宽度即图片宽度，随卡片宽度等比伸缩（1:1 初始）；高度由内容撑开，永不出现滚动条 */
.card.image-only .body.read :deep(.md-preview img) {
  width: 100%;
  max-width: 100%;
  max-height: none;
  height: auto;
}
.card.image-only .body.read {
  overflow: hidden;
}
.body.edit {
  background: transparent;
  font-family: var(--font-ui);
  color: var(--c-text);
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
  padding-top: 30px;
}
.body.edit::placeholder {
  color: var(--c-text-3);
}

/* Measurement twin of the editing textarea: same box and type metrics, but laid out off-screen
   so it reports the height the text needs instead of the height the card happens to have. */
.body.measure {
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
.preview {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: hidden;
}
.preview.empty-hint {
  color: var(--c-text-3);
  font-style: italic;
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
