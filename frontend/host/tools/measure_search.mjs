import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const browser = await chromium.launch({ executablePath: CANDIDATES.find((p) => p && existsSync(p)), args: ['--headless=new'] })
const page = await browser.newPage()
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
const box = await page.locator('.title-search').boundingBox()
const header = await page.locator('.list-headers').boundingBox()
const row = await page.locator('.note-row').first().boundingBox()
const styles = await page.locator('.title-search').evaluate((el) => {
  const s = getComputedStyle(el)
  return { padding: s.padding, margin: s.margin, height: s.height, boxSizing: s.boxSizing }
})
console.log(JSON.stringify({
  searchBox: { h: Math.round(box.height), w: Math.round(box.width) },
  listHeaders: { h: Math.round(header.height) },
  noteRow: { h: Math.round(row.height) },
  computed: styles,
}, null, 1))
await browser.close()
