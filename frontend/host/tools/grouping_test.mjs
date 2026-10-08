/**
 * 分组树：在「全部笔记」下建分组、无限嵌套、把笔记放进分组（右键菜单 + 拖拽）。
 *
 *   node tools/grouping_test.mjs
 *
 * 说明：拖拽用真实 DragEvent + DataTransfer 派发，驱动的是页面里同一套 handler
 * （dragover/drop 读 dataTransfer 里的 MIME），比模拟鼠标轨迹稳定，且不绕过任何业务逻辑。
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const API = 'http://127.0.0.1:45917'
const APP = 'http://localhost:5173'
const RUN = Date.now().toString().slice(-5)
const ROOT = `测试分组${RUN}`
const CHILD = `子分组${RUN}`
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

/** 清掉本测试建的分组（按标题前缀）与新建的笔记。 */
const createdNotes = []
async function trackNewNote() {
  const rows = (await api('GET', '/api/notes?limit=1')).body ?? []
  if (rows[0]) createdNotes.push(rows[0].id)
}

async function cleanup() {
  const groups = (await api('GET', '/api/notebooks')).body ?? []
  for (const g of groups) {
    if (g.title.startsWith('测试分组') || g.title.startsWith('子分组') || g.title.startsWith('重命名')) {
      await api('DELETE', `/api/notebooks/${g.id}`)
    }
  }
  for (const id of createdNotes) {
    await api('DELETE', `/api/notes/${id}?hard=true`)
  }
}
await cleanup()

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
win.on('pageerror', (e) => errors.push(String(e)))
win.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

/** window.prompt 的应答（confirm 一律同意；accept 不接受布尔，confirm 要空参）。 */
let dialogAnswer = ''
win.on('dialog', async (d) => {
  if (d.type() === 'confirm') await d.accept()
  else await d.accept(dialogAnswer)
})

const groupRow = (title) => win.locator('.sidebar .group-item', { hasText: title }).first()

await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2500)

// ---------------------------------------------------------------- 建分组（树）
// 「新建分组」不再单独占一行：它就长在「全部笔记」的图标上（悬停 ▤ 变 ＋）
check('侧栏没有单独的「新建分组」行', (await win.locator('.sidebar .group-add').count()) === 0)
const allRow = win.locator('.sidebar .nav-item:not(.plugin-entry)', { hasText: '全部笔记' }).first()
await allRow.hover()
await win.waitForTimeout(300)
const swapVisible = await allRow.locator('.ico-swap .add').isVisible()
check('悬停「全部笔记」时图标变成 ＋', swapVisible)

dialogAnswer = ROOT
await allRow.locator('.ico-swap').click()
await win.waitForTimeout(1600)
check('侧栏「全部笔记」下出现新建的分组', (await groupRow(ROOT).count()) === 1, ROOT)

// 默认分组（收集箱）应该在，且不能被删除
check('默认分组「收集箱」在树里', (await groupRow('收集箱').count()) === 1)
await groupRow('收集箱').click({ button: 'right' })
await win.waitForTimeout(500)
const deleteEntry = win.locator('.ctx-menu button', { hasText: '删除分组' }).first()
check('默认分组的「删除分组」被禁用', await deleteEntry.isDisabled())
// 菜单靠「点到外面」关闭（没有 Escape 处理），点一处空白把它收起来
await win.mouse.click(720, 520)
await win.waitForTimeout(400)

// 在 ROOT 上右键 → 新建子分组
await groupRow(ROOT).click({ button: 'right' })
await win.waitForTimeout(500)
dialogAnswer = CHILD
await win.locator('.ctx-menu button', { hasText: '新建子分组' }).first().click()
await win.waitForTimeout(1600)
check('子分组建好后父分组自动展开', (await groupRow(CHILD).count()) === 1, CHILD)

// 层级：子分组有缩进（paddingLeft 更大）
const indent = await win.evaluate(([root, child]) => {
  const rows = [...document.querySelectorAll('.sidebar .group-item')]
  const find = (t) => rows.find((r) => (r.textContent ?? '').includes(t))
  const a = find(root)
  const b = find(child)
  if (!a || !b) return null
  return { root: parseFloat(getComputedStyle(a).paddingLeft), child: parseFloat(getComputedStyle(b).paddingLeft) }
}, [ROOT, CHILD])
check('子分组比父分组缩进一层', !!indent && indent.child > indent.root,
  indent ? `父 ${indent.root}px < 子 ${indent.child}px` : '找不到行')

// 收起父分组：子分组随父一起隐藏
await groupRow(ROOT).locator('.caret').click()
await win.waitForTimeout(600)
check('收起父分组后子分组隐藏', (await groupRow(CHILD).count()) === 0)
await groupRow(ROOT).locator('.caret').click()
await win.waitForTimeout(600)
check('再展开后子分组回来', (await groupRow(CHILD).count()) === 1)

// 展开状态要活过刷新（树的展开态像文件管理器一样被记住）
await win.reload({ waitUntil: 'networkidle' })
await win.waitForTimeout(2500)
check('刷新后展开状态仍在（子分组依然可见）', (await groupRow(CHILD).count()) === 1)

// ---------------------------------------------------------------- 拖拽：列表里的笔记 → 分组
const noteTitle = (await win.locator('.note-row .title').first().innerText()).trim()
const noteId = await win.evaluate(() => {
  const row = document.querySelector('.note-row')
  return row ? row.getAttribute('data-node-id') : null
})
const rootId = ((await api('GET', '/api/notebooks')).body ?? []).find((g) => g.title === ROOT)?.id

if (noteId && rootId) {
  await win.evaluate(({ mime, value, groupTitle }) => {
    const rows = [...document.querySelectorAll('.sidebar .group-item')]
    const target = rows.find((r) => (r.textContent ?? '').includes(groupTitle))
    const dt = new DataTransfer()
    dt.setData(mime, value)
    target.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true }))
    target.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true }))
  }, { mime: 'application/x-thisnode-node', value: noteId, groupTitle: ROOT })
  await win.waitForTimeout(1800)

  const inGroup = await api('GET', `/api/notes/${noteId}/blocks`).then(() => null).catch(() => null)
  const detail = (await api('GET', `/api/notes/${noteId}`)).body
  check('拖到分组后：该笔记只属于这个分组（单归属）',
    (detail?.containers ?? []).length === 1 && (detail?.containers ?? [])[0].id === rootId,
    (detail?.containers ?? []).map((c) => c.title).join(' · ') || '（无）')
  void inGroup
} else {
  check('拿到笔记与分组的 id', false, `noteId=${noteId} rootId=${rootId}`)
}

// 点这个分组：右侧只列它的笔记
await groupRow(ROOT).click()
await win.waitForTimeout(1600)
const listedTitles = await win.locator('.note-row .title').allTextContents()
check('点分组后右侧列表被它过滤', listedTitles.some((t) => t.trim() === noteTitle),
  `分组内 ${listedTitles.length} 篇`)

// ---------------------------------------------------------------- 右键菜单：加入分组…
await win.goto(`${APP}/#/notes`, { waitUntil: 'networkidle' })
await win.waitForTimeout(1600)
const childId = ((await api('GET', '/api/notebooks')).body ?? []).find((g) => g.title === CHILD)?.id
await win.locator('.note-row').first().click({ button: 'right' })
await win.waitForTimeout(500)
await win.locator('.ctx-menu button', { hasText: '移到分组' }).first().click()
await win.waitForTimeout(700)
const pickerRows = await win.locator('.gp-row').allTextContents()
check('选择器列出了分组（含子分组）',
  pickerRows.some((t) => t.includes(ROOT)) && pickerRows.some((t) => t.includes(CHILD)),
  pickerRows.map((t) => t.trim().replace(/\s+/g, ' ')).join(' | '))
await win.locator('.gp-row', { hasText: CHILD }).first().click()
await win.waitForTimeout(1600)
const afterPick = (await api('GET', `/api/notes/${noteId}`)).body
check('通过选择器也能改归属（单归属）',
  (afterPick?.containers ?? []).length === 1 && (afterPick?.containers ?? [])[0].id === childId,
  (afterPick?.containers ?? []).map((c) => c.title).join(' · '))

// ---------------------------------------------------------------- 归属：必填 + 自动带入
// 列表里显示归属分组。注意先回到「全部笔记」：上一步用选择器把笔记移到了子分组，
// 而当前列表还过滤在父分组上（已经空了），空列表当然没有 chip —— 这是测试顺序问题。
await allRow.click()
await win.waitForTimeout(1800)
await win.waitForSelector('.note-row .group-chip', { timeout: 8000 }).catch(() => undefined)
const chipTexts = await win.locator('.note-row .group-chip').allTextContents()
check('笔记列表显示归属分组', chipTexts.length > 0,
  chipTexts.slice(0, 3).map((t) => t.trim().replace(/\s+/g, ' ')).join(' | '))

// 在某个分组里新建 → 自动落进这个分组
await groupRow(ROOT).click()
await win.waitForTimeout(1500)
const beforeCount = ((await api('GET', '/api/notebooks')).body ?? []).find((g) => g.id === rootId)?.count ?? 0
await win.locator('.new-note').click()
await win.waitForTimeout(2000)
await trackNewNote()
const afterGroup = ((await api('GET', '/api/notebooks')).body ?? []).find((g) => g.id === rootId)
check('在分组里新建：笔记自动带入当前分组', (afterGroup?.count ?? 0) === beforeCount + 1,
  `${beforeCount} → ${afterGroup?.count ?? '?'}`)

// 顶层新建 → 落进默认分组（走用户路径：点「全部笔记」，它会清掉当前分组）
await allRow.click()
await win.waitForTimeout(1800)
const inboxBefore = ((await api('GET', '/api/notebooks')).body ?? [])
  .find((g) => g.id === 'grp-default')?.count ?? 0
await win.locator('.new-note').click()
await win.waitForTimeout(2000)
await trackNewNote()
const inboxAfter = ((await api('GET', '/api/notebooks')).body ?? [])
  .find((g) => g.id === 'grp-default')?.count ?? 0
check('顶层新建：落进默认分组「收集箱」', inboxAfter === inboxBefore + 1,
  `${inboxBefore} → ${inboxAfter}`)

// 每篇新建的笔记都必须有归属（不变量）
const orphanCheck = await win.evaluate(async (api) => {
  const rows = await (await fetch(`${api}/api/notes?limit=50`)).json()
  let orphans = 0
  for (const r of rows) {
    const d = await (await fetch(`${api}/api/notes/${r.id}`)).json()
    if (!d.containers || d.notebooks.length === 0) orphans++
  }
  return { total: rows.length, orphans }
}, API)
check('没有无归属的笔记', orphanCheck.orphans === 0,
  `${orphanCheck.total} 篇里 ${orphanCheck.orphans} 篇无归属`)

await groupRow(CHILD).click({ button: 'right' })
await win.waitForTimeout(500)
dialogAnswer = `重命名${RUN}`
await win.locator('.ctx-menu button', { hasText: '重命名' }).first().click()
await win.waitForTimeout(1500)
check('重命名生效', (await groupRow(`重命名${RUN}`).count()) === 1)

await groupRow(ROOT).click({ button: 'right' })
await win.waitForTimeout(500)
await win.locator('.ctx-menu button', { hasText: '删除分组' }).first().click()
await win.waitForTimeout(1600)
check('删除分组后它从侧栏消失', (await groupRow(ROOT).count()) === 0)
check('删分组不删笔记', (await api('GET', `/api/notes/${noteId}`)).ok)

check('全程无运行时错误', errors.length === 0, errors.slice(0, 2).join(' | ') || '无')

await cleanup()
await browser.close()
console.log(`\n检查: ${passed}/${passed + failed} 通过`)
if (failed) process.exitCode = 1
