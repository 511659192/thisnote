/** Diagnostic: how much blank page surface is actually clickable? */

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

await page.goto(BASE, { waitUntil: 'networkidle', timeout: 60000 })
await page.waitForTimeout(2500)
await page.locator('.note-row').first().click()
await page.waitForTimeout(2500)

const info = await page.evaluate(() => {
  const s = document.querySelector('.page-surface').getBoundingClientRect()
  const cards = [...document.querySelectorAll('article.card')].map((el) => {
    const r = el.getBoundingClientRect()
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
  })
  // how much of the surface is covered by cards?
  let covered = 0
  let total = 0
  for (let y = Math.round(s.y) + 4; y < s.y + s.height - 4; y += 12) {
    for (let x = Math.round(s.x) + 4; x < s.x + s.width - 4; x += 12) {
      total++
      const el = document.elementFromPoint(x, y)
      if (!el || !el.classList.contains('page-surface')) covered++
    }
  }
  // widest blank strip on the right of the topmost card
  const top = cards[0]
  return {
    surface: { x: Math.round(s.x), y: Math.round(s.y), w: Math.round(s.width), h: Math.round(s.height) },
    cards,
    coveredPercent: Math.round((covered / total) * 100),
    blankRightOfFirstCard: top ? Math.round(s.x + s.width - (top.x + top.w)) : null,
  }
})
console.log(JSON.stringify(info, null, 2))

// Try double-clicking in the widest blank area we can find.
const spot = await page.evaluate(() => {
  const s = document.querySelector('.page-surface').getBoundingClientRect()
  for (let y = s.y + s.height - 60; y > s.y + 60; y -= 40) {
    for (let x = s.x + 40; x < s.x + s.width - 40; x += 40) {
      const el = document.elementFromPoint(x, y)
      if (el && el.classList.contains('page-surface')) return { x, y }
    }
  }
  return null
})
console.log('blank spot found:', JSON.stringify(spot))
if (spot) {
  const before = await page.locator('article.card').count()
  await page.mouse.dblclick(spot.x, spot.y)
  await page.waitForTimeout(2000)
  const after = await page.locator('article.card').count()
  console.log(`double-click blank: ${before} -> ${after}`)
}

await browser.close()
