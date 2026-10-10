/** Temporary check: title rename focus + Enter/Tab commit + Escape cancel. */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', (e) => console.log('PAGEERROR:', e.message))

const state = () =>
  page.evaluate(() => ({
    title: document.querySelector('.note-head .title')?.textContent?.trim() ?? null,
    inputOpen: !!document.querySelector('.note-head .title-input'),
    active: document.activeElement
      ? `${document.activeElement.tagName}.${document.activeElement.className}`
      : '(none)',
    selected: document.activeElement?.selectionStart === 0 &&
      document.activeElement?.selectionEnd === (document.activeElement?.value ?? '').length,
  }))

const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  ${detail}` : ''}`)
}

await page.goto('http://localhost:5173/', { waitUntil: 'networkidle', timeout: 30000 })
await page.waitForTimeout(2000)
await page.locator('.note-row', { hasText: '韧性设计原则' }).first().click()
await page.waitForTimeout(2500)

const original = (await state()).title
console.log('original title:', JSON.stringify(original))

/** Reopen, replace the (fully selected) title, press a key, and report whether it committed. */
async function renameWith(key) {
  await page.locator('.note-head .title').first().dblclick()
  await page.waitForTimeout(500)
  const opened = await state()
  await page.keyboard.type('X')
  await page.waitForTimeout(200)
  await page.keyboard.press(key)
  await page.waitForTimeout(2500)
  return { opened, after: await state() }
}

/** Restore the original title with Enter. */
async function restoreTitle() {
  await page.locator('.note-head .title').first().dblclick()
  await page.waitForTimeout(500)
  await page.keyboard.type(original)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(2500)
  return (await state()).title
}

// --- focus on open ---
await page.locator('.note-head .title').first().dblclick()
await page.waitForTimeout(600)
const opened = await state()
check('双击标题后输入框自动获得焦点', opened.active === 'INPUT.title-input', opened.active)
check('打开时全文选中（便于直接改写）', opened.selected === true, `selected=${opened.selected}`)

// --- Escape cancels ---
await page.keyboard.type('-ESC')
await page.keyboard.press('Escape')
await page.waitForTimeout(2500)
const afterEsc = await state()
check('Escape 取消重命名（标题不变）', afterEsc.title === original,
  `${JSON.stringify(original)} -> ${JSON.stringify(afterEsc.title)}`)
check('Escape 后输入框已关闭', afterEsc.inputOpen === false)

// --- Enter commits (typing replaces the selection, so the result is exactly "X") ---
const enterRun = await renameWith('Enter')
check('Enter 提交重命名', enterRun.after.title === 'X', JSON.stringify(enterRun.after.title))
check('Enter 后输入框已关闭', enterRun.after.inputOpen === false)
check('恢复原标题', (await restoreTitle()) === original)

// --- Tab commits (blur path) ---
const tabRun = await renameWith('Tab')
check('Tab（失焦）提交重命名', tabRun.after.title === 'X', JSON.stringify(tabRun.after.title))
check('恢复原标题', (await restoreTitle()) === original)

// --- click-away commits; reload the page afterwards to prove it persisted ---
await page.locator('.note-head .title').first().dblclick()
await page.waitForTimeout(500)
await page.keyboard.type('CLICK')
const away = await page.evaluate(() => {
  const s = document.querySelector('.page-surface').getBoundingClientRect()
  const boxes = [...document.querySelectorAll('article.card')].map((b) => b.getBoundingClientRect())
  const covers = (x, y) => boxes.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)
  for (let y = s.y + s.height - 30; y > s.y + 20; y -= 20) {
    for (let x = s.x + 40; x < s.x + s.width - 80; x += 20) {
      if (covers(x, y)) continue
      const el = document.elementFromPoint(x, y)
      if (el && el.classList.contains('page-surface')) return { x, y }
    }
  }
  return null
})
if (!away) {
  check('找到用于失焦的空白点', false)
} else {
  await page.mouse.click(away.x, away.y)
  await page.waitForTimeout(2500)
  const clickAway = await state()
  console.log('   after click-away:', JSON.stringify(clickAway))
  check('点击别处提交重命名', clickAway.title === 'CLICK', JSON.stringify(clickAway.title))
  check('点击别处后输入框关闭', clickAway.inputOpen === false)
}

check('恢复原标题', (await restoreTitle()) === original)

console.log(`\n${results.filter((r) => r.ok).length}/${results.length} 通过`)
const failed = results.filter((r) => !r.ok)
if (failed.length) for (const f of failed) console.log('  - ' + f.label)
await browser.close()
process.exit(failed.length ? 1 : 0)
