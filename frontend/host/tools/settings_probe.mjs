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
await page.goto('http://localhost:5173/#/settings', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
const backBtn = await page.getByText('← 返回笔记').count()
// 点返回按钮
await page.getByText('← 返回笔记').click()
await page.waitForTimeout(600)
const urlAfterBack = page.url()
// 再进设置，点 ⚙（应切换回笔记）
await page.goto('http://localhost:5173/#/settings', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await page.locator('.topbar button[title*="设置"]').click()
await page.waitForTimeout(600)
const urlAfterGear = page.url()
console.log(JSON.stringify({ backBtn, urlAfterBack, urlAfterGear, errors: errors.slice(0, 3) }, null, 1))
await browser.close()
