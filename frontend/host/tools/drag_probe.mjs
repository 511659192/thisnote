/** Focused drag probe: does a card follow the pointer? Reports coordinates each step. */

import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const BASE = process.argv[2] || 'http://localhost:5173/'
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))
const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
page.on('pageerror', (e) => console.log(`[pageerror] ${e.message}`))

// Close the backlinks panel: it overlays the right part of the page surface where a card may
// sit, which sent an earlier version of this probe to the wrong element.
await page.addInitScript(() => {
  localStorage.setItem('thisnote.rightPanel', 'closed')
})

await page.goto(BASE, { waitUntil: 'networkidle', timeout: 60000 })
await page.waitForTimeout(2500)
// Close the backlinks panel if it is open: it covers the right edge of the page surface, and a
// card sitting there would otherwise be unreachable for a drag.
const panelToggle = page.locator('.topbar button', { hasText: '▤' })
if (await page.locator('aside.panel').count()) {
  const toggle = page.locator('.topbar .btn.ghost.icon').nth(2)
  if (await toggle.count()) {
    await toggle.click()
    await page.waitForTimeout(600)
  }
  console.log('panel present after toggle attempt:', await page.locator('aside.panel').count())
}
await page.locator('.note-row').first().click()
await page.waitForTimeout(2500)

const blockStyle = () =>
  page.evaluate(() => {
    const el = document.querySelector('article.block')
    const dbg = window.__pageDrag ?? null
    const store = window.__pageStore
    return {
      domTransform: el ? el.style.transform : null,
      storeX: store?.blocks?.[0]?.block?.x ?? null,
      storeY: store?.blocks?.[0]?.block?.y ?? null,
      drag: dbg,
    }
  })

console.log('before:', JSON.stringify(await blockStyle()))

const box = await page.locator('article.block').first().boundingBox()
console.log('boundingBox:', JSON.stringify(box))

/**
 * Find a point on any card that lies inside the visible page surface. A card can sit partly
 * outside the viewport (the page is a free surface, and the backlinks panel shrinks the visible
 * part), so only the overlap region is safe to press.
 */
const grab = await page.evaluate(() => {
  const surface = document.querySelector('.page-surface').getBoundingClientRect()
  const cards = [...document.querySelectorAll('article.block')]
  const diagnostics = []
  for (const card of cards) {
    const r = card.getBoundingClientRect()
    const left = Math.max(r.x, surface.x) + 10
    const right = Math.min(r.x + r.width, surface.x + surface.width) - 10
    const top = Math.max(r.y, surface.y) + 10
    const bottom = Math.min(r.y + r.height, surface.y + surface.height) - 10
    diagnostics.push({
      caption: card.querySelector('.caption')?.textContent ?? '?',
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      visible: [Math.round(right - left), Math.round(bottom - top)],
    })
    if (right - left < 40 || bottom - top < 30) continue
    const x = Math.round((left + right) / 2)
    const y = Math.round(top + 6)
    const el = document.elementFromPoint(x, y)
    if (el && card.contains(el)) {
      return { x, y, caption: card.querySelector('.caption')?.textContent }
    }
  }
  return {
    none: true,
    surface: [Math.round(surface.x), Math.round(surface.y),
              Math.round(surface.width), Math.round(surface.height)],
    diagnostics,
  }
})
if (grab.none) {
  console.log('=> no reachable card:', JSON.stringify(grab, null, 2))
  await browser.close()
  process.exit(1)
}
console.log(`grabbing card "${grab.id}" at (${Math.round(grab.x)},${Math.round(grab.y)})`)

// Sanity check the store mutation path in isolation: if this does nothing, the bug is in
// localMove/reactivity rather than in the pointer bookkeeping.
const direct = await page.evaluate(() => {
  const store = window.__pageStore
  const id = store.blocks[0].block.id
  const before = { x: store.blocks[0].block.x, y: store.blocks[0].block.y }
  store.localMove([{ id, x: 1234, y: 321 }])
  const after = { x: store.blocks[0].block.x, y: store.blocks[0].block.y }
  const domAfter = document.querySelector('article.block').style.transform
  // Is the object the child renders the same object the store mutates?
  const childView = window.__pageChildView?.()
  return {
    id,
    before,
    after,
    domAfter,
    sameObject: childView ? childView.block === store.blocks[0].block : 'no hook',
    childX: childView?.block?.x ?? null,
  }
})
console.log('direct localMove:', JSON.stringify(direct))
await page.evaluate(() => {
  const store = window.__pageStore
  const b = store.blocks[0].block
  b.x = 1000
  b.y = 320
})
await page.waitForTimeout(600)

const start = { x: grab.x, y: grab.y }

// Does a bare mousedown on the card reach a listener at all?
await page.evaluate(() => {
  window.__downs = []
  document.addEventListener(
    'pointerdown',
    (e) => window.__downs.push(`pointerdown on ${e.target.tagName}.${e.target.className}`),
    true,
  )
  document.addEventListener(
    'mousedown',
    (e) => window.__downs.push(`mousedown on ${e.target.tagName}.${e.target.className}`),
    true,
  )
})
await page.mouse.move(box.x + box.width / 2, box.y + 16)
await page.mouse.down()
await page.waitForTimeout(200)
await page.mouse.up()
console.log('document-level events seen:', JSON.stringify(await page.evaluate(() => window.__downs), null, 2))

// Grab the card in an uncovered part of its header area.
await page.mouse.move(start.x, start.y)
await page.mouse.down()
console.log('after mousedown:', JSON.stringify(await blockStyle()))
for (let i = 1; i <= 6; i++) {
  await page.mouse.move(start.x + i * 25, start.y + i * 15)
  await page.waitForTimeout(60)
  console.log(`  move ${i}:`, JSON.stringify(await blockStyle()))
}
await page.mouse.up()
await page.waitForTimeout(2500)
console.log('after mouseup:', JSON.stringify(await blockStyle()))
const after = await page.locator('article.block').first().boundingBox()
console.log('final boundingBox:', JSON.stringify(after))
console.log(
  Math.abs(after.x - box.x) > 30 || Math.abs(after.y - box.y) > 30
    ? '=> DRAG WORKS'
    : '=> DRAG DID NOT MOVE THE CARD',
)

await browser.close()
