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
page.on('pageerror', (e) => errors.push(String(e)))
// 深链接：直接打 /#/notes/abc —— 占位页应在路由注册后接回
await page.goto('http://localhost:5173/#/notes/abc', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const afterDeepLink = page.url()
// 打开第一篇真实笔记（若列表非空）
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
const firstNote = page.locator('.note-row, [class*="note-item"], .list .nav-item').first()
let opened = null
if (await firstNote.count()) {
  await firstNote.click()
  await page.waitForTimeout(1200)
  opened = page.url()
}
const cardsArea = await page.locator('[class*="note-page"], [class*="canvas"], .page').count()
// 生产兜底：notes 界面侧走 /plugin-assets（dev server 关掉的场景在别处验证过产物 200）
console.log(JSON.stringify({ afterDeepLink, opened, cardsArea, errors: errors.slice(0, 3) }, null, 1))
await browser.close()
