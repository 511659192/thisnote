<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CardView } from '@/api/types'
import { host } from '@thisnode/plugin-api'

/** Markdown 渲染由宿主服务提供（markdown-it 只在宿主打一份）。 */
const MarkdownView = host().MarkdownView
// '@/plugins' 触发插件自注册；registry 提供列表（直接导入，绕开类型推断上的环）。
import '@/plugins'
import { activeCardPlugins } from '@/plugins'
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
 * Each card also remembers how its own content is shown — source, source beside preview, or
 * preview only — because a card holding a table wants a different view from one holding a line
 * of prose.
 */
const props = defineProps<{
  view: CardView
  selected: boolean
  editing: boolean
  focused: boolean
}>()

const emit = defineEmits<{
  (e: 'pointerdown', p: { event: PointerEvent; view: CardView; mode: 'move' | 'resize'; handle?: string }): void
  (e: 'request-edit', id: string): void
  (e: 'context', p: { event: MouseEvent; view: CardView }): void
  (e: 'save', p: { cardId: string; contentMd: string }): void
  (e: 'end-edit'): void
  (e: 'toggle-collapse', id: string): void
  (e: 'focus', id: string): void
  (e: 'fit-height', p: { cardId: string; h: number }): void
  // 卡片正文里的 wiki 链接/标签：预览组件只负责认出来，跳转要靠这里往上抛
  (e: 'open-note', id: string): void
  (e: 'create-note', title: string): void
  (e: 'open-tag', tag: string): void
}>()

/** How a card's content is displayed. */
type ContentMode = 'source' | 'split' | 'preview'

const MODES: { mode: ContentMode; label: string; glyph: string }[] = [
  { mode: 'source', label: '源码', glyph: '≡' },
  { mode: 'split', label: '双列', glyph: '◫' },
  { mode: 'preview', label: '预览', glyph: '¶' },
]

const contentMode = ref<ContentMode>('preview')
/**
 * Whether the user picked a mode themselves.
 *
 * Leaving a card returns it to the preview unless the user deliberately chose source or split:
 * an explicit choice is a standing preference, whereas merely writing in a card is not.
 */
const modeChosen = ref(false)

const draft = ref('')
const loadedFor = ref<string | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
/** Off-screen copy of the draft, used only to measure how tall the text really is. */
const measure = ref<HTMLElement | null>(null)
/** 预览态的渲染容器：量它里面内容根节点的自然高度。 */
const readBody = ref<HTMLElement | null>(null)

const card = computed(() => props.view.card)
const content = computed(() => card.value.contentMd ?? '')
/** True while this card shows an editable textarea (source or split). */
const sourceVisible = computed(() => !card.value.collapsed && contentMode.value !== 'preview')

/**
 * Switch how this card shows its content; picking a writing mode starts the write.
 *
 * Choosing preview clears the "explicit choice" flag rather than setting it: preview is the
 * resting state, so writing in the card afterwards should still open the source.
 */
function setMode(mode: ContentMode) {
  modeChosen.value = mode !== 'preview'
  contentMode.value = mode
  if (mode === 'preview') {
    // Preview has no textarea: leave the edit, which saves what was typed.
    if (props.editing) emit('end-edit')
    return
  }
  if (!props.editing) emit('request-edit', card.value.id)
}

/**
 * What the preview shows. While source and preview sit side by side the preview follows the
 * textarea keystroke by keystroke — that is the point of the split view.
 */
const showDraftInPreview = computed(() => contentMode.value === 'split' && sourceVisible.value)
const previewSource = computed(() => (showDraftInPreview.value ? draft.value : content.value))

watch(
  () => [props.editing, card.value.id] as const,
  async ([editing, id]) => {
    if (!editing) {
      // Leaving the card returns it to the preview, unless the user chose a mode themselves.
      if (!modeChosen.value) contentMode.value = 'preview'
      return
    }
    // Writing needs the source: a card showing a preview must reveal its textarea.
    if (!modeChosen.value) contentMode.value = 'source'
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
  () => card.value.id,
  () => {
    loadedFor.value = null
    draft.value = ''
  },
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
  emit('save', { cardId: card.value.id, contentMd: text })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('end-edit')
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
  if (Math.abs(target - card.value.h) > 8) {
    emit('fit-height', { cardId: card.value.id, h: target })
  }
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
 * 只在内容真的变了（打字、外部改写、切显示模式）之后再观察，尺寸变化（图片加载、markdown 重排）
 * 才会继续触发贴合。
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
watch([() => props.editing, () => contentMode.value], () => {
  void nextTick(() => fitHeight())
})
watch(() => content.value, () => fitHeight())
onBeforeUnmount(() => {
  fitObserver?.disconnect()
  observedEl = null
})
</script>

<template>
  <article
    class="card"
    :data-card-id="card.id"
    :class="{ selected, editing, collapsed: card.collapsed, focused }"
    :style="{
      transform: `translate3d(${card.x}px, ${card.y}px, 0)`,
      width: `${card.w}px`,
      height: card.collapsed ? '26px' : `${card.h}px`,
      zIndex: card.z + 1,
    }"
    @pointerdown="onBodyPointerDown"
    @dblclick.stop="emit('request-edit', card.id)"
    @contextmenu.prevent.stop="emit('context', { event: $event, view })"
    @click="emit('focus', card.id)"
  >
    <!-- Card tools: hidden until the card is hovered or selected, so they cost no layout space
         and the content starts right at the top of the card. 插件贡献的开关排在最左。 -->
    <div class="card-tools">
      <template v-if="!card.collapsed">
        <component
          :is="plugin.cardComponent"
          v-for="plugin in activeCardPlugins()"
          :key="plugin.id"
          :card-id="card.id"
        />
      </template>
      <div v-if="!card.collapsed" class="modes" role="group" aria-label="卡片显示模式">
        <button
          v-for="m in MODES"
          :key="m.mode"
          class="mode"
          :class="{ active: contentMode === m.mode }"
          :title="`${m.label}模式`"
          :aria-pressed="contentMode === m.mode"
          @pointerdown.stop
          @click.stop="setMode(m.mode)"
        >
          {{ m.glyph }}
        </button>
      </div>
      <button
        class="collapse"
        :class="{ active: card.collapsed }"
        :title="card.collapsed ? '展开卡片' : '折叠卡片'"
        @pointerdown.stop
        @click.stop="emit('toggle-collapse', card.id)"
      >
        {{ card.collapsed ? '▸' : '▾' }}
      </button>
    </div>

    <!-- body: source / source-beside-preview / preview -->
    <div
      v-if="!card.collapsed && contentMode === 'split'"
      class="body split"
    >
      <textarea
        ref="textarea"
        v-model="draft"
        class="edit pane"
        spellcheck="false"
        placeholder="在这里写…（Ctrl+Enter 保存，Esc 退出）"
        @pointerdown.stop
        @dblclick.stop
        @keydown="onKeydown"
        @blur="commit"
        @input="fitHeight"
      ></textarea>
      <div class="read pane">
        <component :is="MarkdownView"
          v-if="previewSource"
          :content="previewSource"
          @open="(id: string) => emit('open-note', id)"
          @create="(title: string) => emit('create-note', title)"
          @tag="(tag: string) => emit('open-tag', tag)"
        />
        <div v-else class="preview empty-hint">写点什么，这里会出现预览</div>
      </div>
    </div>

    <textarea
      v-else-if="!card.collapsed && contentMode === 'source'"
      ref="textarea"
      v-model="draft"
      class="body edit"
      spellcheck="false"
      placeholder="在这里写…（Ctrl+Enter 保存，Esc 退出）"
      @pointerdown.stop
      @dblclick.stop
      @keydown="onKeydown"
      @blur="commit"
      @input="fitHeight"
    ></textarea>

    <div v-else-if="!card.collapsed" ref="readBody" class="body read">
      <component :is="MarkdownView"
        v-if="content"
        :content="content"
        @open="(id: string) => emit('open-note', id)"
        @create="(title: string) => emit('create-note', title)"
        @tag="(tag: string) => emit('open-tag', tag)"
      />
      <div v-else class="preview empty-hint">点击写点什么</div>
    </div>

    <!-- Off-screen copy of the draft at the textarea's real text width: measuring this, instead
         of the textarea, is what makes "grow to fit" report the content height rather than the
         card's own height. Kept outside the branches above so it exists in every writing mode. -->
    <div
      v-if="sourceVisible"
      ref="measure"
      class="body measure"
      :class="contentMode === 'split' ? 'measure-pane' : 'measure-full'"
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

/* --- card tools: an overlay row in the top-right corner ---
    The container itself never fades: each child owns its own visibility, because the plugin
    chips (e.g. the memory-curve badge) must stay visible on an enabled card even at rest. */
.card-tools {
  position: absolute;
  top: 4px;
  right: 6px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 2px;
}
.modes,
.collapse {
  /* Hidden by default: these cost no layout space, so the content starts at the top of the
     card. They fade in on hover, and stay while the card is selected or being written in. */
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .modes,
.card.selected .modes,
.card.focused .modes,
.card.editing .modes,
.card.collapsed .modes,
.card:hover .collapse,
.card.selected .collapse,
.card.focused .collapse,
.card.editing .collapse,
.card.collapsed .collapse {
  opacity: 1;
}

/* --- per-card view mode --- */
.modes {
  display: flex;
  align-items: center;
  gap: 1px;
}
.mode {
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 12px;
  line-height: 1;
}
.mode:hover {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.mode.active {
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 18%, transparent);
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
}
.body.edit {
  background: transparent;
  font-family: var(--font-ui);
  color: var(--c-text);
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
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
/* The twin must be as wide as the textarea it stands in for: half the card in split mode,
   the whole card otherwise. */
.measure-full {
  width: 100%;
}
.measure-pane {
  width: 50%;
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

/* --- split: source on the left, live preview on the right --- */
.body.split {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  gap: 0;
  padding: 0;
}
.body.split .pane {
  flex: 1 1 50%;
  min-width: 0;
  min-height: 0;
  padding: 12px 14px;
  overflow-y: auto;
}
.body.split .edit.pane {
  border-right: 1px solid var(--c-border);
  /* Explicit colours: a bare textarea falls back to the UA `field` system colour, which is a
     dark box that the theme's text colour cannot be read on. */
  background: transparent;
  color: var(--c-text);
  font-family: var(--font-ui);
  border-top: none;
  border-bottom: none;
  border-left: none;
  outline: none;
  resize: none;
}
.body.split .edit.pane::placeholder {
  color: var(--c-text-3);
}
.body.split .read.pane {
  user-select: text;
  overflow-x: hidden;
  background: transparent;
  color: var(--c-text);
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
