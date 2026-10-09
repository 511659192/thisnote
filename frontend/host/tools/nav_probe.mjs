import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const errors = []
const executablePath = CANDIDATES.find((p) => p && existsSync(p))
const browser = await chromium.launch(executablePath ? { executablePath, args: ['--headless=new'] } : {})
const page = await browser.newPage()
page.on('pageerror', (e) => errors.push('PAGEERROR ' + String(e).slice(0, 220)))
page.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text().slice(0, 220)) })

const snap = async (label) => ({
  label,
  url: page.url(),
  rows: await page.locator('.note-row').count(),
  emptyHint: await page.locator('.list-empty').count(),
  loading: await page.locator('.list-loading').count(),
  sidebarRows: await page.locator('.sidebar-panel .nav-item').count(),
})

const steps = []
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
steps.push(await snap('1. notes'))

// 点设置
await page.locator('.topbar button[title*="设置"]').click()
await page.waitForTimeout(1000)
steps.push(await snap('2. settings'))

// 点复习（侧栏nav入口）
const review = page.locator('.sidebar-panel .nav-item', { hasText: '复习' }).first()
if (await review.count()) {
  await review.click()
  await page.waitForTimeout(1200)
}
steps.push(await snap('3. review'))

// 回笔记：点「全部笔记」
const all = page.locator('.sidebar-panel .nav-item', { hasText: '全部笔记' }).first()
if (await all.count()) {
  await all.click()
  await page.waitForTimeout(1500)
}
steps.push(await snap('4. back to notes'))

// 再点品牌回笔记
await page.locator('.brand').click()
await page.waitForTimeout(1500)
steps.push(await snap('5. brand -> notes'))

console.log(JSON.stringify({ steps, errors: errors.slice(0, 8) }, null, 1))
await browser.close()
