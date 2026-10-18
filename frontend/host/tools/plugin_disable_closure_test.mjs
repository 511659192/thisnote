/**
 * 「停用插件」是否真闭环。
 *
 * 检查的是**界面侧有没有跟着变**：后端闸门早就拦得住端点（409），但如果界面侧还留着入口，
 * 用户看到的就是「点了没反应 / 页面说没有到期卡片」——那不叫停用，叫坏掉。
 *
 * 覆盖：停用后侧栏入口消失、卡片徽标消失、复习页给出明确解释且不产生 409、
 * **装载器不再拉该插件的界面资源**；重新启用后四者恢复。结束时把插件状态还原成开始时的样子。
 *
 * <p>注：界面资源的闸门在**装载器**（停用即不装载微应用、插槽注销），不再由后端把关 ——
 * 后端只提供 API，界面资源由界面侧静态服务提供。
 *
 *   node tools/plugin_disable_closure_test.mjs
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const API = process.env.THISNOTE_API ?? 'http://127.0.0.1:45917'
const APP = process.env.THISNOTE_APP ?? 'http://localhost:5173'
const PLUGIN = 'memory-cycle'
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

async function setActive(value) {
  const res = await fetch(`${API}/api/plugins/active?pluginId=${PLUGIN}&value=${value}`, { method: 'POST' })
  return res.ok
}

/**
 * 停用是**外部**发起的（不经页面），这个窗口的注册表不会自己知道。真实的用户场景是
 * 「在别处停用，然后切回窗口」——切回即触发 focus，可用性快照随之刷新。这里显式派发
 * 同一个事件来模拟它；可用性快照不再跟着切路由拉（每次点开笔记打一遍 /api/plugins 不值得）。
 */
async function settleAvailability() {
  await win.evaluate(() => window.dispatchEvent(new Event('focus')))
  await win.waitForTimeout(600)
}

let startedActive = true
try {
  const list = await (await fetch(`${API}/api/plugins`)).json()
  startedActive = list.find((p) => p.id === PLUGIN)?.active ?? true
} catch (e) {
  console.log(`跳过：后端不可达（${e.message}）`)
  process.exit(0)
}

let passed = 0
let failed = 0
const check = (label, ok, detail = '') => {
  if (ok) { passed++; console.log(`  PASS  ${label}  ${detail}`) }
  else { failed++; console.log(`  FAIL  ${label}  ${detail}`) }
}

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })

/** 打开一个笔记页，返回「侧栏插件入口数 / 卡片徽标数 / 运行时错误」的快照。 */
async function snapshot(collectErrors) {
  const errors = []
  if (collectErrors) {
    win.on('pageerror', (e) => errors.push(String(e)))
    win.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  }
  await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
  await win.waitForTimeout(1800)
  await win.locator('.note-row').first().click()
  await win.waitForSelector('.page-surface', { timeout: 15000 })
  await win.waitForTimeout(2500)
  // 点卡片只为让它进入选中态（未开启时的徽标靠 .card.selected 才可见）；
  // force 旁路命中检测 —— 卡片可能停在笔记标题栏下面，那会拦截指针事件。
  await win.locator('article.card').first().click({ force: true })
  await win.waitForTimeout(600)
  const navEntry = win.locator('.sidebar .nav-item.plugin-entry', { hasText: '复习' })
  const chip = win.locator('article.card').first().locator('.mc-chip')
  return { nav: await navEntry.count(), chip: await chip.count(), errors }
}

async function reviewText() {
  await win.goto(`${APP}/#/review`, { waitUntil: 'networkidle' })
  await win.waitForTimeout(2200)
  return ((await win.locator('.review-view').innerText().catch(() => '')) ?? '').replace(/\s+/g, ' ')
}

// ---------------------------------------------------------------- 启用状态
await setActive(true)
const on = await snapshot(false)
check('启用时：侧栏有插件入口', on.nav === 1, `${on.nav} 个`)
check('启用时：卡片有记忆周期徽标', on.chip === 1, `${on.chip} 个`)

// ---------------------------------------------------------------- 停用
// 界面资源的请求计数：停用后装载器不该再拉这个插件（dev server 入口或 /plugin-assets 都算）
let assetHits = 0
win.on('request', (req) => {
  const u = req.url()
  if (u.includes(`/plugin-assets/${PLUGIN}/`) || u.includes('localhost:7112/')) assetHits++
})
await setActive(false)
await settleAvailability()
const off = await snapshot(false)
check('停用后：侧栏入口消失', off.nav === 0, `${off.nav} 个`)
check('停用后：卡片徽标消失', off.chip === 0, `${off.chip} 个`)

// 停用即卸载：装载器不该再拉这个插件的界面资源（闸门在装载器，不在后端）
check('停用后：装载器不再拉该插件的界面资源', assetHits === 0, `${assetHits} 次请求`)

const stillQueue = await reviewText()
check('停用后：复习页说明原因而不是假装没有卡片',
  stillQueue.includes('已停用') && stillQueue.includes('去设置里启用'),
  stillQueue.slice(0, 60))

// 停用状态下不应该再产生任何 409（入口都没了，就不该再去调被拦的端点）
const errors = []
win.on('pageerror', (e) => errors.push(String(e)))
win.on('console', (m) => {
  if (m.type() === 'error' && /409|Conflict/i.test(m.text())) errors.push(m.text())
})
await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
await win.waitForTimeout(1200)
await win.locator('.note-row').first().click()
await win.waitForSelector('.page-surface', { timeout: 15000 })
await win.waitForTimeout(2500)
await reviewText()
check('停用后：不再产生被拒请求（0 个 409）', errors.length === 0,
  errors.slice(0, 2).join(' | ') || '无')

// ---------------------------------------------------------------- 恢复
await setActive(true)
await settleAvailability()
const back = await snapshot(false)
check('重新启用：侧栏入口回来', back.nav === 1, `${back.nav} 个`)
check('重新启用：卡片徽标回来', back.chip === 1, `${back.chip} 个`)
check('重新启用：装载器重新拉取界面资源', assetHits > 0, `${assetHits} 次请求`)
const backQueue = await reviewText()
check('重新启用：复习页回到正常队列', backQueue.includes('待复习') || backQueue.includes('没有到期的卡片'),
  backQueue.slice(0, 50))

// 还原成开始时的状态（这个测试会改状态，不能留下副作用）
await setActive(startedActive)
console.log(`  （已还原插件状态：active=${startedActive}）`)

await browser.close()
console.log(`\n检查: ${passed}/${passed + failed} 通过`)
if (failed) process.exitCode = 1
