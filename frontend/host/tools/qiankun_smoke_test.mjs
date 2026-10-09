/**
 * qiankun 微前端改造的冒烟测试：
 *  1. 宿主 5173 打开，无致命控制台错误；
 *  2. 记忆曲线微应用挂载 → 侧栏出现「🧠 复习」入口；
 *  3. 深链接 #/review 渲染出插件页面（共享 vue-router 生效）；
 *  4. 收藏微应用挂载 → 新建笔记标题旁出现「☆ 收藏」工具；
 *  5. 停用/启用闭环走界面生命周期（不刷新页面）。
 * 运行：node tools/qiankun_smoke_test.mjs（需要 backend + 宿主 + 插件 dev servers）
 */
import { chromium } from 'playwright'

const BASE = process.env.BASE_URL ?? 'http://localhost:5173'
const errors = []
const failed = (name, detail) => {
  console.error(`✗ ${name}${detail ? ` — ${detail}` : ''}`)
  process.exitCode = 1
}
const ok = (name) => console.log(`✓ ${name}`)

const browser = await chromium.launch({
  // 本机 playwright 缓存里的 chromium 与包版本不完全匹配时，回退到已装的可执行文件
  executablePath: process.env.CHROME_PATH ??
    'C:/Users/51165/AppData/Local/ms-playwright/chromium-1208/chrome-win64/chrome.exe',
})
const page = await browser.newPage()
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(msg.text())
})
page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`))

// 1. 宿主加载
await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(3500) // 等后端探活 + 微应用 mount

const qkMounts = await page.evaluate(() => document.querySelectorAll('#plugin-microapps [data-name], #plugin-microapps div').length)

// 2. 侧栏「复习」入口（记忆曲线微应用注册）
const navReview = await page.locator('a:has-text("复习"), button:has-text("复习"), [title*="复习"]').count()
if (navReview > 0) ok('记忆曲线微应用已挂载：侧栏出现「复习」入口')
else failed('侧栏缺少「复习」入口（memory-curve 微应用未注册？）')

// 3. 深链接 #/review（共享 vue-router + 插件路由）
await page.goto(`${BASE}/#/review`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2500)
const reviewVisible = await page.locator('text=复习').first().isVisible().catch(() => false)
const notRedirected = !page.url().endsWith('#/notes')
if (reviewVisible && notRedirected) ok('深链接 #/review 渲染出插件页面')
else failed('#/review 未渲染', `url=${page.url()}`)

// 4. 收藏工具：建一篇笔记，看标题旁有没有 ☆
await page.goto(`${BASE}/#/notes`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2000)
const newNoteBtn = page.locator('button:has-text("新建"), button:has-text("新建笔记"), [title*="新建"]').first()
if (await newNoteBtn.isVisible().catch(() => false)) {
  await newNoteBtn.click()
  await page.waitForTimeout(1500)
  const favTool = await page.locator('button[title*="收藏"], .note-tools button:has-text("☆")').count()
  if (favTool > 0) ok('收藏微应用已挂载：笔记标题旁出现「☆ 收藏」')
  else failed('笔记工具缺少「☆ 收藏」')
} else {
  console.log('  （没找到「新建」按钮，跳过收藏工具检查）')
}

// 5. 停用 → 启用闭环（qiankun 界面生命周期，不刷新页面；结束后恢复原状）
await page.goto(`${BASE}/#/settings`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)
await page.locator('button:has-text("插件")').first().click()
await page.waitForTimeout(1500)
const mcToggle = page.locator('.plugin-card:has-text("记忆曲线") button:has-text("已启用"), .plugin-card:has-text("记忆曲线") button:has-text("已停用")').first()
if (await mcToggle.isVisible().catch(() => false)) {
  const labelBefore = (await mcToggle.textContent())?.trim()
  page.once('dialog', (d) => d.accept()) // 停用有 window.confirm
  await mcToggle.click()
  await page.waitForTimeout(1200)
  const navGone = (await page.locator('a:has-text("复习"), button:has-text("复习")').count()) === 0
  await mcToggle.click() // 恢复
  await page.waitForTimeout(1500)
  const navBack = (await page.locator('a:has-text("复习"), button:has-text("复习")').count()) > 0
  const labelRestored = (await mcToggle.textContent())?.trim() === labelBefore
  if (navGone && navBack && labelRestored) ok('停用/启用闭环：卸载撤走入口，再装载恢复（无整页刷新）')
  else failed('停用/启用闭环失败', `停用后入口消失=${navGone} 启用后恢复=${navBack} 状态恢复=${labelRestored}`)
} else {
  console.log('  （设置页找不到记忆曲线开关，跳过闭环检查）')
}

// 6. 控制台错误（过滤插件 dev server 探活的正常 404/失败）
const fatal = errors.filter((e) => !/Failed to load resource.*711\d/.test(e) && !/ERR_CONNECTION/.test(e))
if (fatal.length === 0) ok('无控制台错误')
else failed('控制台有错误', fatal.slice(0, 3).join(' | '))

console.log(`  （plugin-microapps 容器内节点数：${qkMounts}）`)
await browser.close()
