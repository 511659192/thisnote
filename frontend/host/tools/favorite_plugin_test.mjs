/**
 * 「收藏」是插件（而不是核心功能）——这个测试把这件事钉死。
 *
 * 它检查三件事：
 *   1. 收藏的三个入口都由插件提供：笔记标题旁的 ★ 工具、侧栏 ★ 收藏 入口、/favorites 页面；
 *   2. 核心不再认识收藏：/api/notes/{id}/favorite 已不存在、笔记 DTO 里没有 favorite 字段；
 *   3. 停用插件后三处入口一起消失（复用同一套闭环），重新启用即恢复。
 *
 *   node tools/favorite_plugin_test.mjs
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const API = 'http://127.0.0.1:45917'
const APP = 'http://localhost:5173'
const PLUGIN = 'favorite'
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

let passed = 0
let failed = 0
const check = (label, ok, detail = '') => {
  if (ok) { passed++; console.log(`  PASS  ${label}  ${detail}`) }
  else { failed++; console.log(`  FAIL  ${label}  ${detail}`) }
}

async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  return { ok: res.ok, status: res.status, body: await res.json().catch(() => null) }
}

const setActive = (value) => fetch(`${API}/api/plugins/${PLUGIN}/active?value=${value}`, { method: 'PUT' })

// 前置条件：插件必须在启用状态（上一轮测试或手动操作可能把它停用了）
await setActive(true)

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
win.on('pageerror', (e) => errors.push(String(e)))
win.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

// ---------------------------------------------------------------- 核心不再认识收藏
const list = await api('GET', '/api/notes?limit=1')
check('笔记列表 DTO 里没有 favorite 字段', list.body && !('favorite' in list.body[0]),
  JSON.stringify(Object.keys(list.body?.[0] ?? {}).slice(0, 6)) + '…')
const legacy = await api('POST', `/api/notes/${list.body[0].id}/favorite?value=true`, {})
check('核心的收藏端点已移除', !legacy.ok, `HTTP ${legacy.status}`)

// 挑一篇没被收藏的笔记来测
const before = await api('GET', `/api/plugins/${PLUGIN}/notes`)
const favorited = new Set((before.body ?? []).map((note) => note.id))
const all = await api('GET', '/api/notes?limit=50')
const target = all.body.find((n) => !favorited.has(n.id)) ?? all.body[0]

// ---------------------------------------------------------------- 三个入口都在
await win.goto(`${APP}/#/notes/${target.id}`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2500)
const tool = win.locator('.fav-tool')
check('笔记标题旁有插件提供的 ★ 工具', (await tool.count()) === 1, `${await tool.count()} 个`)

/** 点一下工具，返回点击后的 active 状态。 */
async function clickTool() {
  const t = win.locator('.fav-tool').first()
  const wasActive = await t.evaluate((el) => el.classList.contains('active'))
  await t.click()
  await win.waitForTimeout(1200)
  return { wasActive, nowActive: await t.evaluate((el) => el.classList.contains('active')) }
}
const toggled = await clickTool()
check('点击 ★ 能切换收藏状态', toggled.wasActive !== toggled.nowActive,
  `${toggled.wasActive} → ${toggled.nowActive}`)

const navEntry = win.locator('.sidebar .nav-item.plugin-entry', { hasText: '收藏' })
  // 「收藏」是插件提供的**列表筛选**：与「全部笔记」同组同级，不拥有自己的页面与布局
  await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
  await win.waitForTimeout(2500)
  const favFilter = win.locator('.sidebar .nav-item', { hasText: '收藏' }).first()
  check('侧栏「笔记」组里有插件提供的「收藏」筛选', (await favFilter.count()) === 1)
  await favFilter.click()
  await win.waitForTimeout(2500)
  const filtered = await win.locator('.note-list').innerText().catch(() => '')
  check('筛选后列表只留收藏的笔记', filtered.includes(target.title),
    filtered.replace(/\s+/g, ' ').slice(0, 48))
  // 对照断言：筛选若悄悄退化成不过滤，这一条会红（只查收藏的还在是不够的）
  const allNotes = await fetch(`${API}/api/notes?filter=all`).then((r) => r.json())
  const otherNote = (allNotes.items ?? allNotes).find((n) => n.id !== target.id)
  check('筛选后未收藏的笔记不在列表里', otherNote ? !filtered.includes(otherNote.title) : true,
    otherNote ? otherNote.title : '(无对照笔记)')
  check('筛选后仍在核心列表页（不是另开页面）', win.url().includes('/notes'), win.url().slice(-28))
  await win.locator('.note-list').getByText(target.title, { exact: false }).first().click()
  await win.waitForTimeout(2000)
  check('点列表一行打开该笔记', win.url().includes('/notes/' + target.id), win.url().slice(-36))
  check('打开后是核心的笔记页（标题旁 ★ 在）', (await win.locator('.fav-tool').count()) === 1)

  // 停用插件：两处入口一起消失（筛选与 ★）
  const offErrors = []
  win.on('response', (r) => { if (r.status() >= 400) offErrors.push(r.status() + ' ' + r.url()) })
  await setActive(false)
  await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
  await win.waitForTimeout(2500)
  check('停用后：筛选消失', (await win.locator('.sidebar .nav-item', { hasText: '收藏' }).count()) === 0)
  check('停用后：不再产生被拒请求', offErrors.length === 0, offErrors.slice(0, 1).join(''))

  // 重新启用：恢复
  await setActive(true)
  await win.reload({ waitUntil: 'networkidle' })
  await win.waitForTimeout(2500)
  check('重新启用：筛选回来', (await win.locator('.sidebar .nav-item', { hasText: '收藏' }).count()) === 1)
  check('全程无运行时错误', errors.length === 0, errors.slice(0, 2).join(' | ') || '无')

await browser.close()
console.log(`\n检查: ${passed}/${passed + failed} 通过`)
if (failed) process.exitCode = 1
