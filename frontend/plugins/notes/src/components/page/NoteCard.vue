<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CardView } from '@/api/types'
import { activeCardPlugins, host, toast } from '@thisnode/plugin-api'
import { deleteUnreferencedImageAttachment, getClipboardImage, uploadImageAttachment } from './clipboardImage'
import { getClipboardMarkdown, getClipboardMarkdownFile, isClipboardCode, isMarkdown, type CardFormat } from './markdownFormat'

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
}>()

const emit = defineEmits<{
  (e: 'pointerdown', p: { event: PointerEvent; view: CardView; mode: 'move' | 'resize'; handle?: string }): void
  (e: 'request-edit', id: string): void
  (e: 'context', p: { event: MouseEvent; view: CardView; imageSrc?: string }): void
  (e: 'save', p: { cardId: string; contentMd: string }): void
  (e: 'end-edit'): void
  (e: 'toggle-collapse', id: string): void
  (e: 'focus', id: string): void
  (e: 'fit-height', p: { cardId: string; h: number; w?: number; fitImage?: boolean }): void
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

const card = computed(() => props.view.card)
const content = computed(() => props.view.contentMd ?? '')
const sourceVisible = computed(() => !card.value.collapsed && props.editing)
const formatMode = ref<CardFormat>('auto')
const formatMenuOpen = ref(false)
const detectedFormat = computed(() => (isMarkdown(props.editing ? draft.value : content.value) ? 'markdown' : 'text'))
const effectiveFormat = computed(() => formatMode.value === 'auto' ? detectedFormat.value : formatMode.value)
const formatIcon = computed(() => formatMode.value === 'auto' ? 'A' : formatMode.value === 'markdown' ? 'M' : 'T')
const detectedFormatLabel = computed(() => detectedFormat.value === 'markdown' ? 'Markdown' : '普通文本')
const formatTitle = computed(() => {
  if (formatMode.value === 'auto') return `自动判定（当前：${detectedFormatLabel.value}）`
  return formatMode.value === 'markdown' ? 'Markdown 格式' : '普通文本格式'
})

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
  formatMenuOpen.value = false
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
    formatMenuOpen.value = false
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
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  const EDGE = 6
  const nearLeft = e.clientX - r.left <= EDGE
  const nearRight = r.right - e.clientX <= EDGE
  const nearTop = e.clientY - r.top <= EDGE
  const nearBottom = r.bottom - e.clientY <= EDGE
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
 * 让卡片高度贴合内容，双向：长文长高、删改后收缩 —— 卡片下部不再留大片空白。
 * 展示上限（长文到浏览器底缘、超出滚动）由页面在 onFitHeight 里按视口折算。
 *
 * <p>编辑态量**屏外 twin**：textarea 会撑满卡片，量它自己等于"自己量自己"，只会越量越高。
 * 预览态直接量渲染出来的内容根节点（它的高度是自然的，不被卡片裁剪）。8px 迟滞防止
 * 一次输入恰好跨过阈值时反复拉扯；88px 下限与页面的 BLOCK_MIN_H 一致。
 */
function fitHeight() {
  if (card.value.collapsed) return
  const el = contentEl()
  if (!el) return
  // 有了真实信号才挂观察器（见 armFitObserver）：避免"每次打开笔记都贴合一次"。
  armFitObserver()
  const needed = Math.round(el.scrollHeight) + 24
  const target = Math.max(BLOCK_MIN_H, needed)
  const images = Array.from(el.querySelectorAll('img')).filter((image) => image.naturalWidth > 0)
  if (autoFitImageWidth.value && images.length) {
    const imageWidth = Math.max(...images.map((image) => image.naturalWidth)) + 32
    autoFitImageWidth.value = false
    if (imageWidth > card.value.w) {
      emit('fit-height', { cardId: card.value.cardId, h: target, w: imageWidth, fitImage: true })
      return
    }
  }
  if (Math.abs(target - card.value.h) > 8) {
    emit('fit-height', { cardId: card.value.cardId, h: target, fitImage: fitPastedImage.value })
  }
  if (!props.editing && images.length) fitPastedImage.value = false
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
watch(() => content.value, () => fitHeight())
onBeforeUnmount(() => {
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
    class="card"
    :data-card-id="card.cardId"
    :class="{ selected, editing, collapsed: card.collapsed, focused }"
    :style="{
      transform: `translate3d(${card.x}px, ${card.y}px, 0)`,
      width: `${card.w}px`,
      height: card.collapsed ? '26px' : `${card.h}px`,
      zIndex: props.zIndex,
    }"
    @pointerdown="onBodyPointerDown"
    @dblclick.stop="emit('request-edit', card.cardId)"
    @contextmenu.prevent.stop="onContextMenu"
    @click="emit('focus', card.cardId)"
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
        class="format-trigger"
        type="button"
        :title="formatTitle"
        :aria-label="`卡片格式：${formatTitle}`"
        :aria-expanded="formatMenuOpen"
        @click="formatMenuOpen = !formatMenuOpen"
      >
        {{ formatIcon }}
      </button>
      <div v-if="formatMenuOpen" class="format-menu" role="menu">
        <button class="format-option" :class="{ active: formatMode === 'auto' }" role="menuitem" @click="selectFormat('auto')">
          <span>自动判定</span><small>当前：{{ detectedFormatLabel }}</small>
        </button>
        <button class="format-option" :class="{ active: formatMode === 'markdown' }" role="menuitem" @click="selectFormat('markdown')">
          <span>Markdown</span>
        </button>
        <button class="format-option" :class="{ active: formatMode === 'text' }" role="menuitem" @click="selectFormat('text')">
          <span>普通文本</span>
        </button>
      </div>
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
    </template>
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
}
.format-trigger {
  width: 22px;
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
.format-trigger:hover,
.format-trigger[aria-expanded='true'] {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.format-menu {
  position: absolute;
  top: 24px;
  left: 0;
  display: grid;
  min-width: 144px;
  gap: 2px;
  padding: 4px;
  border: 1px solid var(--c-border);
  border-radius: 7px;
  background: var(--c-surface);
  box-shadow: var(--shadow);
}
.format-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--c-text);
  font: inherit;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
}
.format-option:hover,
.format-option.active {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
}
.format-option small {
  color: var(--c-text-3);
  font-size: 10px;
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
</style>
