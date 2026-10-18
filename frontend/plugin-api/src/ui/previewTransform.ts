/**
 * 预览浮层图片的滚轮缩放 + 左键拖移（NotePage 与 ReviewView 共用同一套交互）。
 *
 * <p>变换状态挂在图片元素上（WeakMap），浮层关闭时随 DOM 一起丢弃。
 * `grabbingClass` 是各页面自己定义的「抓取中」光标样式；`stopWheelPropagation`
 * 控制滚轮是否阻止冒泡 —— 笔记页的浮层下面是可平移/缩放的画布，必须挡住，
 * 复习页没有画布则不需要。
 */
export function createPreviewImageInteractions(options: {
  grabbingClass: string
  stopWheelPropagation?: boolean
}) {
  const MIN_SCALE = 0.2
  const MAX_SCALE = 8
  type Transform = { scale: number; x: number; y: number }
  const transforms = new WeakMap<HTMLImageElement, Transform>()
  let drag: {
    image: HTMLImageElement
    startX: number
    startY: number
    baseX: number
    baseY: number
  } | null = null

  function transformOf(image: HTMLImageElement): Transform {
    let transform = transforms.get(image)
    if (!transform) {
      transform = { scale: 1, x: 0, y: 0 }
      transforms.set(image, transform)
    }
    return transform
  }

  function applyImgTransform(image: HTMLImageElement) {
    const t = transformOf(image)
    // translate 在 scale 之前，保证拖移位移始终按屏幕像素计算
    image.style.transform = 'translate(' + t.x + 'px, ' + t.y + 'px) scale(' + t.scale + ')'
  }

  function onPreviewWheel(event: WheelEvent) {
    // 预览浮层内的滚轮不落回下层：否则滚动会平移/缩放遮罩后面的内容
    if (options.stopWheelPropagation) event.stopPropagation()
    const image = (event.target as HTMLElement).closest?.('img')
    if (!image) return
    event.preventDefault()
    const transform = transformOf(image)
    const factor = event.deltaY < 0 ? 1.2 : 1 / 1.2
    transform.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, transform.scale * factor))
    applyImgTransform(image)
  }

  function onPreviewMouseDown(event: MouseEvent) {
    if (event.button !== 0) return
    const image = (event.target as HTMLElement).closest?.('img')
    if (!image) return
    event.preventDefault()
    const transform = transformOf(image)
    drag = {
      image,
      startX: event.clientX,
      startY: event.clientY,
      baseX: transform.x,
      baseY: transform.y,
    }
    image.classList.add(options.grabbingClass)
    window.addEventListener('mousemove', onPreviewMouseMove)
    window.addEventListener('mouseup', onPreviewMouseUp)
  }

  function onPreviewMouseMove(event: MouseEvent) {
    if (!drag) return
    const transform = transformOf(drag.image)
    transform.x = drag.baseX + (event.clientX - drag.startX)
    transform.y = drag.baseY + (event.clientY - drag.startY)
    applyImgTransform(drag.image)
  }

  function onPreviewMouseUp() {
    if (!drag) return
    drag.image.classList.remove(options.grabbingClass)
    drag = null
    window.removeEventListener('mousemove', onPreviewMouseMove)
    window.removeEventListener('mouseup', onPreviewMouseUp)
  }

  /** 组件卸载时调用：拖拽进行中也把 window 级监听摘掉，避免泄漏。 */
  function dispose() {
    if (!drag) return
    drag.image.classList.remove(options.grabbingClass)
    drag = null
    window.removeEventListener('mousemove', onPreviewMouseMove)
    window.removeEventListener('mouseup', onPreviewMouseUp)
  }

  return { onPreviewWheel, onPreviewMouseDown, dispose }
}
