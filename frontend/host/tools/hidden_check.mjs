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
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
// 侧栏不应出现「标题搜索」行（hidden 筛选）
const sidebarSearchRow = await page.locator('.sidebar-panel .nav-item', { hasText: '标题搜索' }).count()
// 列表头部的搜索框应存在
const boxInListHeader = await page.locator('.list-headers').getByPlaceholder('按标题搜索…').count()
console.log(JSON.stringify({ sidebarSearchRow, boxInListHeader }, null, 1))
await browser.close()
