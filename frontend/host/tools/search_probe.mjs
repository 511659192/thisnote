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
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)

const box = page.getByPlaceholder('按标题搜索…')
const boxVisible = await box.count()

await box.fill('zz-search-target')
await page.waitForTimeout(1600) // 防抖 250 + load()（冷启动首次转换较慢）
const alphaVisible = await page.getByText('zz-search-target-alpha').count()
const betaVisible = await page.getByText('zz-unrelated-beta').count()
const urlWhileFiltering = page.url()

await box.fill('')
await page.waitForTimeout(700)
const betaBackAfterClear = await page.getByText('zz-unrelated-beta').count()
const countBadge = await page.locator('.title-search .count').textContent().catch(() => null)

console.log(JSON.stringify({
  boxVisible, alphaVisible, betaHidden: betaVisible === 0, urlWhileFiltering,
  countBadgeWhileFiltering: countBadge, betaBackAfterClear, errors: errors.slice(0, 3),
}, null, 1))
await browser.close()
