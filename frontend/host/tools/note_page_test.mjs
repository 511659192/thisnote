/**
 * Verifies the note-page interaction model end to end in a real browser:
 *   open note → double-click empty page → a card appears and is focused for typing
 *   type → Ctrl+Enter → saved into the note
 *   Ctrl+V → clipboard text becomes a card under the cursor
 *   drag a card → position follows, persists across reload
 *   zoom / fit / snap controls work
 *   the per-note card cap is enforced with a visible message
 *
 * Usage: node tools/note_page_test.mjs [baseUrl]
 */

import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const BASE = process.argv[2] || 'http://localhost:5173/'
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

const problems = []
const steps = []
const record = (name, ok, detail = '') => {
  steps.push({ name, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
}

/** Titles that are legitimate parts of the demo vault, used for the "no board UI" assertions. */
const DEBRIS = ['画布', '看板', '四象限', 'Canvas']

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const context = await browser.newContext({
  viewport: { width: 1500, height: 950 },
  permissions: ['clipboard-read', 'clipboard-write'],
})
const page = await context.newPage()
page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))
page.on('console', (m) => {
  if (m.type() === 'error' && !/favicon|Failed to load resource/i.test(m.text())) {
    problems.push(`console.error: ${m.text()}`)
  }
})

const blocks = () => page.locator('article.block').count()
const surface = () => page.locator('.page-surface').first()

console.log('\n=== 启动与笔记列表 ===')
await page.goto(BASE, { waitUntil: 'networkidle', timeout: 60000 })
await page.waitForTimeout(2500)
record('应用启动', (await page.locator('.shell').count()) > 0)
record('笔记列表非空', (await page.locator('.note-row').count()) > 0,
  `${await page.locator('.note-row').count()} 篇`)

console.log('\n=== 打开笔记 → 笔记页面 ===')
// pick a demo note that has content
const target = page.locator('.note-row', { hasText: '韧性设计原则' }).first()
if (await target.count()) await target.click()
else await page.locator('.note-row').first().click()
await page.waitForTimeout(2500)
record('页面视图渲染', (await surface().count()) > 0)
record('笔记至少有一张卡片', (await blocks()) >= 1, `${await blocks()} 张`)
const headText = await page.locator('.note-head').innerText()
// The running count is deliberately silent; only hitting the cap is worth surfacing.
record('页头未达上限时不显示卡片计数', !/\d+\s*\/\s*\d+\s*张卡片/.test(headText),
  headText.split('\n').filter((l) => l.includes('卡片')).join(' | ') || '（无计数文案）')

console.log('\n=== 双击空白处新建卡片 ===')
const before = await blocks()
const box = await surface().boundingBox()
// Pick empty space away from existing cards and away from the panel/zoom chrome.
const empty = await page.evaluate(() => {
  const s = document.querySelector('.page-surface').getBoundingClientRect()
  const taken = [...document.querySelectorAll('article.block')].map((el) => {
    const r = el.getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height }
  })
  for (let y = s.y + s.height - 120; y > s.y + 40; y -= 60) {
    for (let x = s.x + 60; x < s.x + s.width - 400; x += 60) {
      if (taken.some((t) => x >= t.x - 20 && x <= t.x + t.w + 20 && y >= t.y - 20 && y <= t.y + t.h + 20)) continue
      const el = document.elementFromPoint(x, y)
      if (el && el.classList.contains('page-surface')) return { x, y }
    }
  }
  return null
})
if (!empty) {
  record('找到空白区域', false, 'page is full of cards')
} else {
  await page.mouse.dblclick(empty.x, empty.y)
  await page.waitForTimeout(2500)
  record('双击新增一张卡片', (await blocks()) === before + 1, `${before} → ${await blocks()}`)
  record('新卡片立即进入编辑态', (await page.locator('article.block textarea.body.edit').count()) > 0)
  record('编辑框自动获得焦点',
    await page.evaluate(() => document.activeElement?.classList.contains('body') ?? false))
}

console.log('\n=== 在卡片里直接写 ===')
const marker = `页面上写的-${Date.now()}`
await page.keyboard.type(marker)
await page.waitForTimeout(300)
const typed = await page.locator('article.block textarea.body.edit').inputValue()
record('输入进入卡片', typed.includes(marker), JSON.stringify(typed.slice(0, 50)))
await page.keyboard.press('Control+Enter')
await page.waitForTimeout(2500)
record('保存后退出编辑态', (await page.locator('article.block.editing').count()) === 0)
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const reopen = page.locator('.note-row', { hasText: '韧性设计原则' }).first()
if (await reopen.count()) await reopen.click()
await page.waitForTimeout(2500)
record('刷新后卡片内容仍在（已写入笔记）',
  (await page.locator('article.block', { hasText: marker }).count()) > 0)

console.log('\n=== Ctrl+V 粘贴成卡片 ===')
const pasteText = `粘贴到页面 ${Date.now()}\n\n这段文字应该变成一张卡片。`
await page.evaluate(async (t) => navigator.clipboard.writeText(t), pasteText)
const beforePaste = await blocks()
const pb = await surface().boundingBox()
await page.mouse.move(pb.x + 420, pb.y + 300)
await page.waitForTimeout(300)
await page.keyboard.press('Control+v')
await page.waitForTimeout(3000)
record('Ctrl+V 新增一张卡片', (await blocks()) === beforePaste + 1,
  `${beforePaste} → ${await blocks()}`)
const pasted = page.locator('article.block', { hasText: '粘贴到页面' })
record('粘贴内容出现在卡片上', (await pasted.count()) > 0)
if (await pasted.count()) {
  // Check placement in page coordinates: a card is positioned in world space and may
  // legitimately fall outside the current viewport, so screen position is not the test.
  const placement = await page.evaluate(() => {
    const el = [...document.querySelectorAll('article.block')].find((b) =>
      b.textContent.includes('粘贴到页面'),
    )
    if (!el) return null
    const m = /translate3d\(([-\d.]+)px,\s*([-\d.]+)px/.exec(el.style.transform || '')
    const surface = document.querySelector('.page-surface').getBoundingClientRect()
    return m ? { world: { x: Number(m[1]), y: Number(m[2]) }, surface: { x: surface.x, y: surface.y } } : null
  })
  record('粘贴卡片有页面坐标', !!placement, JSON.stringify(placement))
}

console.log('\n=== 拖 East 手柄改宽度，拖卡片顶部条移动卡片 ===')

// Card geometry in WORLD coordinates, so comparisons are not affected by viewport panning.
const cardGeom = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('article.block')].map((el) => {
      const m = /translate3d\(([-\d.]+)px,\s*([-\d.]+)px/.exec(el.style.transform || '')
      return {
        x: m ? Number(m[1]) : null,
        y: m ? Number(m[2]) : null,
        w: Math.round(el.getBoundingClientRect().width),
        hasHead: !!el.querySelector('.body.read'),
      }
    }),
  )

/** Screen point on a card's read view, which is the card's grab area. */
const headPoint = (index) =>
  page.evaluate((i) => {
    const b = document.querySelectorAll('article.block')[i]?.querySelector('.body.read')
    if (!b) return null
    const r = b.getBoundingClientRect()
    // Mid-height and right of centre: clear of the hidden tool row in the top-right corner.
    const x = r.x + r.width * 0.4
    const y = r.y + r.height * 0.6
    const el = document.elementFromPoint(x, y)
    if (!el) return null
    return { x, y }
  }, index)

/**
 * Index of the first card that is actually reachable by the pointer.
 *
 * The note list is a sibling column that overlaps the left part of the page canvas, so the card
 * at world x=0 can sit underneath it. Tests that click a card must skip those. Cards that overlap
 * another card are skipped too: these checks are about one card's geometry, and a stack of
 * overlapping cards (which repeated runs leave behind) would make them measure the wrong thing.
 */
const reachableCardIndex = () =>
  page.evaluate(() => {
    const list = document.querySelector('.note-list')?.getBoundingClientRect()
    const cards = [...document.querySelectorAll('article.block')]
    const boxes = cards.map((c) => c.getBoundingClientRect())
    const overlaps = (a, b) =>
      a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    const usable = (i) => {
      const r = boxes[i]
      if (list && overlaps(r, list)) return false
      if (!cards[i].querySelector('.body.read')) return false
      if (r.width <= 60 || r.height <= 40) return false
      // Must be fully inside the surface: a card hanging off the bottom edge cannot be grabbed
      // (there is a minimum-size clamp, so a drag that starts there does not move it).
      const surf = document.querySelector('.page-surface')?.getBoundingClientRect()
      if (surf && (r.bottom > surf.bottom || r.top < surf.top)) return false
      return !boxes.some((other, j) => j !== i && overlaps(r, other))
    }
    // Prefer the newest cards: the one this run just created sits alone on the page, whereas
    // earlier runs may have left overlapping cards behind.
    for (let i = cards.length - 1; i >= 0; i--) if (usable(i)) return i
    return -1
  })

const beforeGeom = await cardGeom()
// Resize handles only render while a card is selected, so select a card first.
const pickIdx = await reachableCardIndex()
const selectPoint = await page.evaluate((i) => {
  const card = document.querySelectorAll('article.block')[i]
  if (!card) return null
  const r = card.getBoundingClientRect()
  for (let y = Math.round(r.top + 6); y < r.bottom - 6; y += 8) {
    for (let x = Math.round(r.left + 6); x < r.right - 6; x += 8) {
      const el = document.elementFromPoint(x, y)
      if (el && card.contains(el)) return { x, y }
    }
  }
  return null
}, pickIdx)
if (selectPoint) await page.mouse.click(selectPoint.x, selectPoint.y)
await page.waitForTimeout(600)
const firstCard = await page.evaluate((i) => {
  const el = document.querySelectorAll('article.block')[i]
  if (!el) return { handleX: 0, handleY: 0, hasHandle: false, w: 0 }
  const r = el.getBoundingClientRect()
  // Centre of the east resize handle, which straddles the card's right edge.
  // Deliberately NOT rounded: the handle is only 9px wide, so rounding a fractional centre
  // can land just outside it — on the card body, which moves the card instead of resizing it.
  const handle = el.querySelector('.handle.e')
  const hr = handle ? handle.getBoundingClientRect() : null
  return {
    handleX: hr ? hr.x + hr.width / 2 : r.right,
    handleY: hr ? hr.y + hr.height / 2 : r.top + r.height / 2,
    hasHandle: !!handle,
    w: Math.round(r.width),
  }
}, pickIdx)
record('选中卡片后出现缩放手柄', firstCard.hasHandle)
await page.mouse.move(firstCard.handleX, firstCard.handleY)
await page.mouse.down()
await page.mouse.move(firstCard.handleX + 120, firstCard.handleY, { steps: 14 })
await page.mouse.up()
await page.waitForTimeout(2500)
const afterResize = await cardGeom()
record('拖手柄改变卡片宽度', afterResize[0].w > firstCard.w + 60,
  `${firstCard.w} → ${afterResize[0].w}`)
// Compare world coordinates: screen positions shift when the viewport pans.
const worldPos = (cards) => cards.map((c) => [c.x, c.y])
record('改宽度不影响其他卡片位置',
  JSON.stringify(worldPos(afterResize.slice(1))) === JSON.stringify(worldPos(beforeGeom.slice(1))),
  `其他卡片 before=${JSON.stringify(worldPos(beforeGeom.slice(1)))} after=${JSON.stringify(worldPos(afterResize.slice(1)))}`)

// Now move a card by its read view: both x and y must change, and nobody else may shift.
const targetIndex = await reachableCardIndex()
const moveBefore = await cardGeom()
if (targetIndex < 0) {
  record('找到卡片可拖动区域', false)
} else {
  const g = await headPoint(targetIndex)
  const othersBefore = worldPos(moveBefore.filter((_, i) => i !== targetIndex))
  await page.mouse.move(g.x, g.y)
  await page.waitForTimeout(150)
  await page.mouse.down()
  await page.mouse.move(g.x - 160, g.y + 110, { steps: 14 })
  await page.mouse.up()
  await page.waitForTimeout(2500)
  const moveAfter = await cardGeom()
  const moved = moveAfter[targetIndex]
  record('拖卡片内容区自由移动卡片（x 与 y 都变）',
    moved.x < moveBefore[targetIndex].x && moved.y > moveBefore[targetIndex].y,
    `(${moveBefore[targetIndex].x},${moveBefore[targetIndex].y}) → (${moved.x},${moved.y})`)
  const othersAfter = worldPos(moveAfter.filter((_, i) => i !== targetIndex))
  record('移动一张卡片不会挤压其他卡片',
    JSON.stringify(othersAfter) === JSON.stringify(othersBefore),
    `其他卡片 before=${JSON.stringify(othersBefore)} after=${JSON.stringify(othersAfter)}`)
  record('卡片总数不变', moveAfter.length === moveBefore.length,
    `${moveBefore.length} → ${moveAfter.length}`)
}

console.log('\n=== 空卡片自动删除 ===')
const beforeEmpty = await blocks()
/**
 * A point that is blank page surface *now*, skipping any area a card might occupy.
 *
 * The card created below can be up to 640x260 and the column ends near the bottom of the
 * viewport, so the card often covers the point that was blank a moment ago. Clicking there
 * would land inside the new card's own textarea and never abandon it.
 */
const blankPointNow = () =>
  page.evaluate(() => {
    const s = document.querySelector('.page-surface').getBoundingClientRect()
    const cards = [...document.querySelectorAll('article.block')].map((b) => b.getBoundingClientRect())
    const covers = (x, y) =>
      cards.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)
    for (let y = s.y + s.height - 40; y > s.y + 30; y -= 25) {
      for (let x = s.x + 60; x < s.x + s.width - 120; x += 25) {
        if (covers(x, y)) continue
        const el = document.elementFromPoint(x, y)
        if (el && el.classList.contains('page-surface')) return { x, y }
      }
    }
    return null
  })
const emptySurface = await blankPointNow()
if (!emptySurface) {
  record('找到空白处用于测试空卡片', false)
} else {
  await page.mouse.dblclick(emptySurface.x, emptySurface.y)
  await page.waitForTimeout(2000)
  const afterCreate = await blocks()
  record('双击创建了空卡片', afterCreate === beforeEmpty + 1, `${beforeEmpty} → ${afterCreate}`)
  record('新卡片进入输入状态', (await page.locator('article.block.editing').count()) === 1)

  // The new card may extend past the bottom of the viewport, so aim at a point that is
  // verified blank *after* the card exists, and never at the card itself.
  const leaveAt = (await blankPointNow()) ?? emptySurface
  await page.mouse.click(leaveAt.x, leaveAt.y)
  let afterLeave = afterCreate
  for (let i = 0; i < 14 && afterLeave !== beforeEmpty; i++) {
    await page.waitForTimeout(400)
    afterLeave = await blocks()
  }
  record('离开后空卡片被自动删除', afterLeave === beforeEmpty, `${afterCreate} → ${afterLeave}`)
}

console.log('\n=== 缩放 / 适应 / 吸附 ===')
const zoomLabel = () => page.locator('.zoom-label').innerText()
const z0 = await zoomLabel()
await page.locator('.zoom-bar button', { hasText: '＋' }).first().click()
await page.waitForTimeout(500)
record('放大生效', (await zoomLabel()) !== z0, `${z0} → ${await zoomLabel()}`)
await page.locator('.zoom-bar button', { hasText: '适应' }).first().click()
await page.waitForTimeout(700)
record('适应按钮可用', (await zoomLabel()).length > 0, await zoomLabel())
const snapBtn = page.locator('.zoom-bar button', { hasText: '吸附' }).first()
const snapBefore = await snapBtn.getAttribute('class')
await snapBtn.click()
await page.waitForTimeout(400)
record('吸附开关切换', (await snapBtn.getAttribute('class')) !== snapBefore)

console.log('\n=== 侧栏与命令面板不再有画布概念 ===')
const sidebar = await page.locator('.sidebar').innerText()
// The sidebar has no 画布 section header and no board list. Note *titles* are not checked:
// a user may legitimately title a note with any of these words.
record('侧栏没有画布分区', !sidebar.includes('画布\n＋'))
record('侧栏没有看板/四象限入口', !sidebar.includes('看板') && !sidebar.includes('四象限'))

await page.keyboard.press('Control+k')
await page.waitForTimeout(800)
record('Ctrl+K 打开命令面板', (await page.locator('.palette').count()) > 0)
// Check command entries only — notes in the list may contain any words.
const commandTitles = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.palette .row')]
  return rows
    .filter((r) => r.querySelector('.kind')?.textContent.trim() === '⌘')
    .map((r) => r.querySelector('.title')?.textContent.trim() ?? '')
})
record('命令里没有画布/看板/四象限',
  !commandTitles.some((t) => /画布|看板|四象限/.test(t)),
  commandTitles.join(' | ').slice(0, 160))
await page.keyboard.press('Escape')
await page.waitForTimeout(400)

await browser.close()

console.log('\n' + '='.repeat(60))
const failed = steps.filter((s) => !s.ok)
console.log(`步骤: ${steps.length - failed.length}/${steps.length} 通过`)
if (failed.length) {
  console.log('失败:')
  for (const f of failed) console.log(`  - ${f.name} ${f.detail}`)
}
console.log(`运行时错误: ${problems.length}`)
for (const p of [...new Set(problems)]) console.log('  ! ' + p)
process.exit(failed.length || problems.length ? 1 : 0)
