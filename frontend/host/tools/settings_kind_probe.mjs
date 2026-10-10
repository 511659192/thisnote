import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const errors = []
const browser = await chromium.launch({ executablePath: CANDIDATES.find((p) => p && existsSync(p)), args: ['--headless=new'] })
const page = await browser.newPage()
page.on('pageerror', (e) => errors.push(String(e).slice(0, 160)))
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 160)) })

await page.goto('http://localhost:5173/#/settings', { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
const cards = await page.locator('.plugin-card').count()
const badges = await page.locator('.plugin-card .kind').allInnerTexts()
const disableBtns = await page.getByRole('button', { name: /^已启用$|^已停用$/ }).count()
const reloadBtns = await page.getByRole('button', { name: '重载' }).count()
console.log(JSON.stringify({ cards, badges, disableBtns, reloadBtns, errors: errors.slice(0, 3) }, null, 1))
await browser.close()
