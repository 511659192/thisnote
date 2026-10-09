import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]
const errors = []
const executablePath = CANDIDATES.find((p) => p && existsSync(p))
const browser = await chromium.launch({ executablePath, args: ['--headless=new'] })
const page = await browser.newPage()
page.on('pageerror', (e) => errors.push(String(e).slice(0, 200)))
page.on('console', (m) => { if (m.type() === 'log' && m.text().includes('[search]')) errors.push('LOG ' + m.text().slice(0, 120)) })
await page.goto('http://localhost:5173/#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
const box = page.getByPlaceholder('按标题搜索…')
await box.fill('zz-search-target')
await page.waitForTimeout(1200)
const html = await page.evaluate(() => document.querySelector('.title-search')?.innerHTML ?? 'NO .title-search')
console.log(JSON.stringify({ html, errors: errors.slice(0, 3) }, null, 1))
await browser.close()
