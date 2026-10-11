/**
 * Full-surface browser walkthrough: opens every view and exercises the main interactions,
 * collecting any page error, console error or failed request.
 *
 * Written after a runtime crash ("Config merge conflict for field override") took the whole
 * note editor down while every server-side and type-level check stayed green — only a real
 * browser catches that class of bug.
 *
 * Model note: a note *is* a page of cards (README §「核心模型」). The page editor is no longer
 * CodeMirror — it is `.page-surface` holding `article.card` cards — so this walkthrough asserts
 * the card page (create / leave / prune), never the removed `.editor-shell` / `.cm-editor`.
 *
 * Usage: node tools/browser_walkthrough.mjs [baseUrl]
 */

import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const BASE = process.argv[2] || 'http://localhost:5173/'

const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

const problems = []
const steps = []

function record(step, ok, detail = '') {
  steps.push({ step, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${step}${detail ? `  ${detail}` : ''}`)
}

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))
page.on('console', (m) => {
  if (m.type() === 'error' && !/favicon|Failed to load resource/i.test(m.text())) {
    problems.push(`console.error: ${m.text()}`)
  }
})
page.on('requestfailed', (r) => problems.push(`requestfailed: ${r.method()} ${r.url()}`))

const text = () => page.evaluate(() => document.body.innerText)
const count = (sel) => page.locator(sel).count()
const cards = () => page.locator('article.card').count()

/** Count that tolerates a selector which never appears, instead of throwing. */
const countSoon = async (sel, timeout = 5000) => {
  try {
    await page.locator(sel).first().waitFor({ timeout })
    return await count(sel)
  } catch {
    return 0
  }
}

/**
 * A point that is blank page surface right now — no card covers it.
 *
 * Cards can be up to 640x260 and the column ends near the bottom of the viewport, so a point
 * that was blank a moment ago may sit inside a card that has just been added. Clicking there
 * would land in that card's own textarea instead of abandoning it.
 */
const blankPointNow = () =>
  page.evaluate(() => {
    const s = document.querySelector('.page-surface')?.getBoundingClientRect()
    if (!s) return null
    const boxes = [...document.querySelectorAll('article.card')].map((b) => b.getBoundingClientRect())
    const covers = (x, y) =>
      boxes.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)
    for (let y = s.y + s.height - 40; y > s.y + 30; y -= 25) {
      for (let x = s.x + 60; x < s.x + s.width - 120; x += 25) {
        if (covers(x, y)) continue
        const el = document.elementFromPoint(x, y)
        if (el && el.classList.contains('page-surface')) return { x, y }
      }
    }
    return null
  })

console.log(`\n=== 打开 ${BASE} ===`)
await page.goto(BASE, { waitUntil: 'networkidle', timeout: 30000 })
await page.waitForTimeout(2000)
record('应用启动（非白屏）', (await count('.shell')) > 0)
record('侧栏渲染', (await count('.sidebar')) > 0)
record('笔记列表非空', (await count('.note-row')) > 0, `${await count('.note-row')} 行`)

console.log('\n=== 笔记页面（卡片） ===')
await page.locator('.note-row', { hasText: '韧性设计原则' }).first().click()
await page.waitForTimeout(2500)
record('打开笔记后显示页面头部', (await count('.note-head')) > 0)
record('页面渲染卡片', (await cards()) > 0, `${await cards()} 张`)
record('卡片显示笔记内容', (await count('article.card', { hasText: '冗余' })) > 0)

console.log('\n=== 双击建卡 / 离开自动删除空卡片 ===')
const blank = await blankPointNow()
if (!blank) {
  record('找到页面空白处', false, 'no blank point on the surface')
} else {
  const before = await cards()
  await page.mouse.dblclick(blank.x, blank.y)
  await page.waitForTimeout(2500)
  const afterCreate = await cards()
  record('双击空白创建卡片', afterCreate === before + 1, `${before} → ${afterCreate}`)
  record('新卡片进入输入状态', (await count('article.card.editing')) === 1)

  const leaveAt = (await blankPointNow()) ?? blank
  await page.mouse.click(leaveAt.x, leaveAt.y)
  let afterLeave = afterCreate
  for (let i = 0; i < 14 && afterLeave !== before; i++) {
    await page.waitForTimeout(400)
    afterLeave = await cards()
  }
  record('空卡片离开后自动删除', afterLeave === before, `${afterCreate} → ${afterLeave}`)
}

console.log('\n=== 缩放控件 ===')
const zoomLabel = () => page.locator('.zoom-label').innerText()
if (await count('.zoom-bar')) {
  const z0 = await zoomLabel()
  await page.locator('.zoom-bar button', { hasText: '＋' }).first().click()
  await page.waitForTimeout(500)
  record('放大生效', (await zoomLabel()) !== z0, `${z0} → ${await zoomLabel()}`)
  const fit = page.locator('.zoom-bar button', { hasText: '适应' }).first()
  if (await fit.count()) {
    await fit.click()
    await page.waitForTimeout(800)
    record('适应内容不报错', (await count('.page-surface')) > 0, await zoomLabel())
  }
} else {
  record('缩放控件存在', false)
}

console.log('\n=== 笔记信息面板 ===')
const panelToggle = page
  .locator('.topbar button[title*="右侧面板"]')
  .first()
if (await panelToggle.count()) {
  if (!(await count('.panel'))) {
    await panelToggle.click()
    await page.waitForTimeout(900)
  }
  record('笔记信息面板可打开', (await count('.panel')) > 0)
} else {
  record('右侧面板开关存在', false)
}

console.log('\n=== 编辑器标题重命名 ===')
const titleEl = page.locator('.note-head .title').first()
if (await titleEl.count()) {
  const original = await titleEl.innerText()
  await titleEl.dblclick()
  await page.waitForTimeout(500)
  const input = page.locator('.note-head .title-input').first()
  record('双击标题进入重命名', (await input.count()) > 0)
  if (await input.count()) {
    const shown = await input.inputValue()
    record('重命名输入框有值', shown.length > 0, shown)
    await page.keyboard.press('Escape')
    // Esc swaps the <input> back for the <h2>, so wait for it rather than reading it at once.
    await page.locator('.note-head .title').first().waitFor({ timeout: 5000 })
    await page.waitForTimeout(300)
    record('Esc 取消重命名不报错', (await page.locator('.note-head .title').first().innerText()) === original)
  }
} else {
  record('笔记标题存在', false)
}

console.log('\n=== 命令面板 (Ctrl+K) ===')
await page.keyboard.press('Control+k')
record('Ctrl+K 打开命令面板', (await countSoon('.palette')) > 0)
if (await count('.palette')) {
  await page.locator('.palette .search').fill('韧性')
  await page.waitForTimeout(900)
  record('命令面板有搜索结果', (await count('.palette .row')) > 0, `${await count('.palette .row')} 条`)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

console.log('\n=== 搜索视图 ===')
await page.goto(BASE + '#/search', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const searchInput = page.locator('.search-input').first()
if (await searchInput.count()) {
  await searchInput.fill('冗余')
  await page.waitForTimeout(1200)
  record('搜索返回结果', (await count('.hit')) > 0, `${await count('.hit')} 条`)
  if (await count('.hit')) {
    await page.locator('.hit').first().click()
    await page.waitForTimeout(900)
    record('搜索结果右侧预览渲染', (await count('.preview-pane')) > 0)
  }
} else {
  record('搜索输入框存在', false)
}

console.log('\n=== 设置视图 ===')
for (const [tab, label] of [
  ['本地文件', '镜像'],
  ['快照与维护', '快照'],
  ['关于', '关于'],
]) {
  await page.goto(BASE + '#/settings', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const btn = page.locator('.tabs button', { hasText: tab }).first()
  if (await btn.count()) {
    await btn.click()
    await page.waitForTimeout(900)
    const body = await text()
    record(`设置页「${tab}」渲染`, body.length > 100, `${label} ${body.length} 字符`)
  } else {
    record(`设置页有「${tab}」标签`, false)
  }
}

console.log('\n=== 回收站（入口已移到命令面板） ===')
// 前置：清空回收站。否则列表里是别人的遗留笔记，「空回收站」的空状态就不会出现，
// 这一步就会变成依赖外部状态的脆弱断言。
await fetch('http://127.0.0.1:45917/api/notes/trash', { method: 'DELETE' }).catch(() => undefined)
await page.goto(BASE + '#/notes', { waitUntil: 'networkidle' })
await page.waitForTimeout(900)

// 侧栏只剩「全部笔记 / 最近编辑」两个核心视图：每日笔记、模板、归档、回收站已移除，
// 收藏整体抽成了插件（变成侧栏里的插件入口 ★ 收藏，与核心视图同级但另算）。
const sidebarFilters = await page.locator('.sidebar .nav-item:not(.plugin-entry)').allTextContents()
const filterText = sidebarFilters.map((t) => t.trim().replace(/\s+/g, ' ')).join(' · ')
record('「笔记」组里没有已移除的核心概念（收藏由插件提供、复习是插件页面）',
  !/每日笔记|模板|归档|回收站|复习/.test(filterText),
  filterText)
// 插件入口必须与核心视图同级（同一种 nav-item 行），但**不假设哪些插件正开着**：
// 插件可以被停用，那时它的入口本就该消失。
const pluginEntries = await page.locator('.sidebar .plugin-entry').allTextContents()
const navLevelCount = await page.locator('.sidebar .nav-item.plugin-entry').count()
record('插件入口与核心视图同级（都是 nav-item）',
  navLevelCount === pluginEntries.length,
  pluginEntries.length
    ? pluginEntries.map((t) => t.trim().replace(/\s+/g, ' ')).join(' | ')
    : '（当前没有启用的插件入口）')

await page.keyboard.press('Control+k')
await page.waitForTimeout(500)
await page.keyboard.type('回收站')
await page.waitForTimeout(900)
const trashCommand = page.locator('.results button.row', { hasText: '回收站' }).first()
if (await trashCommand.count()) {
  await trashCommand.click()
  await page.waitForTimeout(1000)
  // 空回收站时必须说清「空的是回收站」，而不是笼统的「这里还没有笔记」
  record('回收站可从命令面板打开且位置可辨', (await text()).includes('回收站'),
    (await page.locator('.list-empty p').first().innerText().catch(() => '')).trim())
} else {
  record('回收站命令存在', false, '命令面板里没找到')
}


console.log('\n=== 画布概念已移除 ===')
const sidebarText = await page.locator('.sidebar').innerText()
record('侧栏没有画布/看板入口', !/画布|看板|四象限/.test(sidebarText))
record('命令面板入口仍在顶栏', (await count('.topbar .palette-trigger')) > 0)

console.log('\n=== 主题切换 ===')
const themeBtn = page
  .locator('.topbar button[title*="切换到浅色"], .topbar button[title*="切换到深色"]')
  .first()
if (await themeBtn.count()) {
  const before = await page.evaluate(() => document.documentElement.dataset.theme)
  await themeBtn.click()
  await page.waitForTimeout(500)
  const after = await page.evaluate(() => document.documentElement.dataset.theme)
  record('主题切换生效', before !== after, `${before} → ${after}`)
}

console.log('\n=== 刷新后状态保持 ===')
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1800)
record('刷新后应用仍可用', (await count('.shell')) > 0)

await browser.close()

console.log('\n' + '='.repeat(60))
const failed = steps.filter((s) => !s.ok)
console.log(`步骤: ${steps.length - failed.length}/${steps.length} 通过`)
if (failed.length) {
  console.log('\n失败步骤:')
  for (const f of failed) console.log(`  - ${f.step} ${f.detail}`)
}
console.log(`\n运行时错误: ${problems.length}`)
for (const p of [...new Set(problems)]) console.log('  ! ' + p)

process.exit(failed.length || problems.length ? 1 : 0)
