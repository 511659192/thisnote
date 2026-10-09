import { existsSync, readFileSync } from 'node:fs'
import { chromium } from 'playwright'

const probe = JSON.parse(readFileSync('/tmp/mc-probe.json', 'utf-8'))
const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const errors = []
const browser = await chromium.launch({ executablePath: CANDIDATES.find((p) => p && existsSync(p)), args: ['--headless=new'] })
const page = await browser.newPage()
page.on('pageerror', (e) => errors.push(String(e).slice(0, 180)))
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 180)) })

// 侧栏徽标：开启后「复习」入口应带计数 1
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const reviewEntryBadge = await page.locator('.sidebar-panel .nav-item', { hasText: '复习' }).innerText()

// 复习页：应渲染该卡片并**带出正文**（view.card 修复的验证点）
await page.goto('http://localhost:5173/#/review', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
const body = await page.locator('body').innerText()
const contentShown = body.includes(probe.content.slice(0, 4))
const gradeButtons = await page.getByText('良好', { exact: true }).count()
const orphanHint = body.includes('孤儿')

console.log(JSON.stringify({
  reviewEntryBadge: reviewEntryBadge.replace(/\s+/g, ' '),
  contentShown,
  gradeButtons,
  orphanHint,
  errors: errors.slice(0, 4),
}, null, 1))
await browser.close()
