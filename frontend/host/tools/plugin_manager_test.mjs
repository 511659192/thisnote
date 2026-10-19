/**
 * 插件管理页的浏览器测试：列表 / 类型徽标 / 版本 / 健康 / 启停 / 通用配置表单。
 *
 * 会真的停用再启用插件（结束时恢复原状：三个插件一律拉回启用态，中途失败也一样）。
 * 后端不可达时跳过。
 *
 *   node tools/plugin_manager_test.mjs
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const APP = 'http://localhost:5173'
const API = 'http://127.0.0.1:45917'
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

let initialActive = true
try {
  const plugins = await (await fetch(`${API}/api/plugins`)).json()
  initialActive = plugins.find((p) => p.id === 'memory-cycle')?.active ?? true
} catch (e) {
  console.log(`跳过：后端不可达（${e.message}）`)
  process.exit(0)
}
// 起点必须是干净的：上一次跑崩了会把插件留在停用态，然后这套测试的第一次点击就变成「启用」。
await ensureAllEnabled()
initialActive = true

let passed = 0
let failed = 0
const check = (label, ok, detail = '') => {
  if (ok) { passed++; console.log(`  PASS  ${label}  ${detail}`) }
  else { failed++; console.log(`  FAIL  ${label}  ${detail}`) }
}

/**
 * 停用/启用现在是真的停/启插件的 Spring 容器（SBP/PF4J 权威），不是改一个标记位，
 * 状态要 1~3 秒才落定 —— 一律轮询等待，不要用固定 sleep。
 */
async function waitPluginActive(id, expected, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      last = (await (await fetch(`${API}/api/plugins/${id}`)).json()).active
      if (last === expected) return true
    } catch { /* 容器启停窗口内请求可能失败，重试 */ }
    await new Promise((r) => setTimeout(r, 400))
  }
  console.log(`    （等待超时：${id} active=${last}，期望 ${expected}）`)
  return false
}

/**
 * 保证三个插件都处于启用态。这套测试会真的把插件停下来，一旦中途失败/中断，
 * 就会把插件留在停用态并让后续套件连锁拿到 409 —— 所以必须无条件收尾。
 */
async function ensureAllEnabled() {
  for (const id of ['favorite', 'memory-cycle']) {
    try {
      const info = await (await fetch(`${API}/api/plugins/${id}`)).json()
      if (info.active !== true) {
        await fetch(`${API}/api/plugins/${id}/active?value=true`, { method: 'PUT' })
        await waitPluginActive(id, true)
      }
    } catch (e) {
      console.log(`    （恢复启用 ${id} 失败：${e.message}）`)
    }
  }
}

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
let stopWindowFrom = 0
let stopWindowTo = 0
win.on('pageerror', (e) => errors.push({ text: String(e), at: Date.now() }))
win.on('console', (m) => { if (m.type() === 'error') errors.push({ text: m.text(), at: Date.now() }) })
// 停用/重载都会先 window.confirm，浏览器里一律接受
win.on('dialog', (d) => void d.accept())

await win.goto(APP + '/#/settings', { waitUntil: 'networkidle' })
await win.waitForTimeout(2000)
await win.locator('.tabs button', { hasText: '插件' }).click()
await win.waitForTimeout(2500)

const panel = win.locator('section.panel')
const cards = panel.locator('.plugin-card')
check('插件列表渲染了两个内置插件', (await cards.count()) >= 2, `${await cards.count()} 个`)

const card = (title) => panel.locator('.plugin-card', { hasText: title })
check('记忆周期标为卡片插件',
  (await card('记忆周期').locator('.kind', { hasText: '卡片' }).count()) === 1)
// 版本号不写死在测试里：它现在是**一处来源**（Maven 版本 → plugin.properties），
// 所以断言"卡片显示的版本 == /api/plugins 报的版本"。
const pluginsInfo = await (await fetch(`${API}/api/plugins`)).json()
const versionOf = (id) => pluginsInfo.find((p) => p.id === id)?.version
const dependenciesOf = (id) => pluginsInfo.find((p) => p.id === id)?.dependencies ?? []
check('显示版本号（与 /api/plugins 一致）',
  (await card('记忆周期').locator('.mono', { hasText: `v${versionOf('memory-cycle')}` }).count()) === 1,
  `memory-cycle=v${versionOf('memory-cycle')}`)
check('Graphify 显式依赖 AI 与 Notes',
  dependenciesOf('graphify').join(',') === 'ai,notes',
  dependenciesOf('graphify').join(','))
// 通用配置表单暂时没有测试对象：剩下两个插件都没声明配置项（带配置的百度网盘插件已暂时移除）。
await card('记忆周期').locator('button', { hasText: '设置' }).click()
await win.waitForTimeout(600)
check('无配置项的插件给出说明',
  (await card('记忆周期').locator('p', { hasText: '没有可配置项' }).count()) === 1)
// 重载：SBP 会重建插件的类加载器与 Spring 子容器（换了 jar / 开发模式改完代码就靠它）
const reloadBtn = card('记忆周期').locator('.plugin-head button', { hasText: '重载' })
check('插件卡片有「重载」按钮', (await reloadBtn.count()) === 1)
await reloadBtn.click()
check('重载后插件仍在服务', await waitPluginActive('memory-cycle', true))
const dueAfterReload = await fetch(`${API}/api/plugins/memory-cycle/due`)
check('重载后插件自有端点仍可用', dueAfterReload.status === 200, `HTTP ${dueAfterReload.status}`)

// 停用 → 按钮与插件状态都变；再启用恢复
const toggle = card('记忆周期').locator('.plugin-head button').last()
try {
  stopWindowFrom = Date.now()
  await toggle.click()
  check('停用后后端状态为 false', await waitPluginActive('memory-cycle', false))
  const toggleText = (await toggle.textContent())?.trim()
  check('按钮文案变为「已停用」', (toggleText ?? '').includes('已停用'), `实际「${toggleText}」`)

  // 停用期间插件自有端点应被拦（说明「停用」是真的拦住了，而不是只改了个标记位）
  const blocked = await fetch(`${API}/api/plugins/memory-cycle/due`)
  check('停用期间自有端点被拦（409）', blocked.status === 409, `HTTP ${blocked.status}`)

  await toggle.click()
  check('重新启用成功', await waitPluginActive('memory-cycle', true), `initialActive=${initialActive}`)
} finally {
  // 无论上面成败，都要把插件放回启用态，否则后续套件全部连锁 409
  await ensureAllEnabled()
  stopWindowTo = Date.now()
}

const unexpected = errors.filter(
  (e) => !(e.at >= stopWindowFrom && e.at <= stopWindowTo && /\b409\b/.test(e.text)),
)
check('无额外运行时错误（停用窗口内的 409 属预期）', unexpected.length === 0,
  unexpected.slice(0, 2).map((e) => e.text).join(' | '))

await browser.close()
console.log(`\n检查: ${passed}/${passed + failed} 通过`)
if (failed) process.exitCode = 1
