import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const errors = []
const executablePath = CANDIDATES.find((p) => p && existsSync(p))
const browser = await chromium.launch(executablePath ? { executablePath, args: ['--headless=new'] } : {})
const page = await browser.newPage()
page.on('pageerror', (e) => errors.push(String(e).slice(0, 150)))
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 150)) })
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const box = page.getByPlaceholder('模糊搜索笔记…')
const boxVisible = await box.count()
await box.fill(process.argv[2] ?? 'this')
await page.waitForTimeout(1200)
const dd = await page.locator('.topbar-search .dropdown').count()
const rows = await page.locator('.topbar-search .result').count()
let opened = null
if (rows > 0) {
  await page.locator('.topbar-search .result').first().click()
  await page.waitForTimeout(1000)
  opened = page.url()
}
console.log(JSON.stringify({ boxVisible, dropdown: dd, resultRows: rows, opened, errors: errors.slice(0, 3) }, null, 1))
await browser.close()
