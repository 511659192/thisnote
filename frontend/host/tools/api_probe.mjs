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
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e)))
await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const url = page.url()
const newNote = await page.locator('button[title="新建笔记"]').count()
const notesPanel = await page.locator('.sidebar-panel').count()
const navRail = await page.getByText('插件', { exact: true }).count()
const reviewEntry = await page.getByText('复习').count()
const microApps = await page.evaluate(() => document.querySelector('#plugin-microapps')?.children.length ?? -1)
// 进入一条笔记页面（只读：打开第一篇再返回，不保存任何内容）
console.log(JSON.stringify({ url, newNote, notesPanel, navRail, reviewEntry, microApps, errors: errors.slice(0, 5) }, null, 1))
await browser.close()
