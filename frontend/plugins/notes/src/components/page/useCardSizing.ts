import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { CardView } from '@/api/types'
import { BLOCK_MIN_H } from '@/stores/notePage'

interface CardSizingProps {
  view: CardView
  editing: boolean
  fitBaseWidth?: number
}

interface CardSizingEmit {
  (e: 'fit-height', p: { cardId: string; h: number; w?: number; fitImage?: boolean; force?: boolean }): void
  (e: 'fit-width', p: { cardId: string; ratio: number }): void
  (e: 'fit-custom', p: { cardId: string; width: number; height: number }): void
}

export function useCardSizing(
  props: CardSizingProps,
  emit: CardSizingEmit,
  card: ComputedRef<CardView['card']>,
  content: ComputedRef<string>,
  isImageOnly: ComputedRef<boolean>,
  readBody: () => HTMLElement | null,
  measure: Ref<HTMLElement | null>,
  textarea: () => HTMLTextAreaElement | null,
  rootEl: Ref<HTMLElement | null>,
  autoFitImageWidth: Ref<boolean>,
  fitPastedImage: Ref<boolean>,
) {
  const imageFitWidth = ref<number | null>(null)
  const customSizeOpen = ref(false)
  const customWidthDraft = ref('')
  const customHeightDraft = ref('')
  let fitObserver: ResizeObserver | null = null
  let observedEl: HTMLElement | null = null

  const activePreset = computed(() => {
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

  function contentEl(): HTMLElement | null {
    return props.editing
      ? measure.value
      : ((readBody()?.firstElementChild as HTMLElement | null) ?? null)
  }

  function updateImageWidth(): HTMLImageElement[] {
    const images = Array.from(contentEl()?.querySelectorAll('img') ?? [])
    const loaded = images.filter((image) => image.naturalWidth > 0)
    imageFitWidth.value = loaded.length
      ? Math.max(...loaded.map((image) => image.naturalWidth)) + 30
      : null
    return images
  }

  function measureMaxContentWidth(): number {
    const body = readBody() ?? measure.value
    if (!body) return 0
    const clone = body.cloneNode(true) as HTMLElement
    clone.style.cssText += ';position:fixed;left:-10000px;top:0;width:max-content;max-width:none;overflow:visible;'
    document.body.appendChild(clone)
    const width = Math.ceil(clone.scrollWidth)
    clone.remove()
    return width
  }

  function armFitObserver(): void {
    const el = contentEl()
    if (!el || el === observedEl) return
    observedEl = el
    if (!fitObserver) fitObserver = new ResizeObserver(() => fitHeight())
    fitObserver.disconnect()
    fitObserver.observe(el)
  }

  function fitHeight(force = false): void {
    if (card.value.collapsed) return
    const el = contentEl()
    if (!el) return
    armFitObserver()

    const images = Array.from(el.querySelectorAll('img'))
    const pendingImages = images.filter((image) => !image.complete)
    if (pendingImages.length) {
      for (const image of pendingImages) {
        if (image.dataset.fitRefitArmed) continue
        image.dataset.fitRefitArmed = '1'
        image.addEventListener('load', () => fitHeight(true), { once: true })
        image.addEventListener('error', () => fitHeight(true), { once: true })
      }
      return
    }
    updateImageWidth()
    const loadedImages = images.filter((image) => image.naturalWidth > 0)
    const waitingForAttachmentImage = autoFitImageWidth.value && loadedImages.some(
      (image) => image.dataset.thisnoteAttachment && image.getAttribute('src') === 'about:blank',
    )

    let needed: number
    const editor = textarea()
    const preview = readBody()
    if (props.editing && editor) {
      const boxStyle = getComputedStyle(editor)
      const verticalPadding = parseFloat(boxStyle.paddingTop) + parseFloat(boxStyle.paddingBottom)
      needed = Math.ceil(el.offsetHeight + verticalPadding) + 2
    } else if (preview) {
      const bodyStyle = getComputedStyle(preview)
      const verticalPadding = parseFloat(bodyStyle.paddingTop) + parseFloat(bodyStyle.paddingBottom)
      needed = Math.ceil(el.offsetHeight + verticalPadding) + 2
    } else {
      return
    }
    let target = Math.max(BLOCK_MIN_H, needed)

    let width: number | undefined
    if (force || autoFitImageWidth.value) {
      const desiredWidths: number[] = []
      const imageWidth = loadedImages.length
        ? Math.max(...loadedImages.map((image) => image.naturalWidth)) + 30
        : 0
      if (imageWidth > 0) desiredWidths.push(imageWidth)
      const maxContentWidth = measureMaxContentWidth()
      if (maxContentWidth > 0) desiredWidths.push(maxContentWidth + 2)
      if (desiredWidths.length) {
        if (!waitingForAttachmentImage) autoFitImageWidth.value = false
        const desired = isImageOnly.value && imageWidth > 0
          ? imageWidth
          : Math.max(...desiredWidths)
        if (Math.abs(desired - card.value.w) > 4) width = desired
      }
    }

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

  function autoFit(): void {
    autoFitImageWidth.value = true
    fitPastedImage.value = true
    fitHeight(true)
  }

  function chooseFitWidth(ratio: number | null): void {
    if (ratio === null) {
      autoFit()
      return
    }
    emit('fit-width', { cardId: card.value.cardId, ratio })
  }

  function chooseCustomSize(): void {
    const imageWidth = isImageOnly.value && imageFitWidth.value ? imageFitWidth.value - 30 : 0
    customWidthDraft.value = String(Math.round(imageWidth > 0 ? imageWidth : card.value.w))
    const root = rootEl.value
    const zoom = root && card.value.w > 0 ? root.getBoundingClientRect().width / card.value.w : 1
    const rendered = root && zoom > 0 ? root.getBoundingClientRect().height / zoom : card.value.h
    customHeightDraft.value = String(Math.round(rendered))
    customSizeOpen.value = true
  }

  function applyCustomSize(): void {
    const width = Number(customWidthDraft.value.trim())
    const height = Number(customHeightDraft.value.trim())
    if (!Number.isFinite(width) || width < 88 || !Number.isFinite(height) || height < 88) return
    customSizeOpen.value = false
    emit('fit-custom', { cardId: card.value.cardId, width: isImageOnly.value ? width + 30 : width, height })
  }

  onMounted(() => {
    void nextTick(() => {
      const images = updateImageWidth()
      for (const image of images) {
        image.addEventListener('load', () => updateImageWidth(), { once: true })
      }
    })
  })
  watch([() => props.editing, () => card.value.w], () => void nextTick(() => fitHeight()))
  watch(() => content.value, () => void nextTick(() => fitHeight()))
  onBeforeUnmount(() => {
    fitObserver?.disconnect()
    observedEl = null
  })

  return {
    activePreset,
    fitHeight,
    autoFit,
    chooseFitWidth,
    customSizeOpen,
    customWidthDraft,
    customHeightDraft,
    chooseCustomSize,
    applyCustomSize,
  }
}
