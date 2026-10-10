/**
 * 卡片正文里的链接必须能点开。
 *
 * 用户报过的 bug：卡片里的 `[[双链]]` 点击没反应。根因是 NoteBlock 里的 MarkdownPreview
 * 没接它的事件（wiki 链接是 `<span data-wikilink>`，没有默认跳转），而 NotePage / NoteView
 * 也没有这一路转发 —— 事件发出来没人听，就等于点了没反应。
 *
 *   node tools/card_link_test.mjs
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const API = 'http://127.0.0.1:45917'
const APP = 'http://localhost:5173'
const RUN = Date.now().toString().slice(-5)
const TARGET = `链接目标${RUN}`
const SOURCE = `链接来源${RUN}`
const TAG = `标签${RUN}`
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
  return { ok: res.ok, body: await res.json().catch(() => null) }
}

const created = []
const target = (await api('POST', '/api/notes', { title: TARGET, contentMd: '目标正文', tags: [] })).body
created.push(target.id)
const source = (await api('POST', '/api/notes', { title: SOURCE, contentMd: '', tags: [] })).body
created.push(source.id)

// 来源笔记的第一张卡片写上：双链 + 标签 + 指向不存在笔记的双链（应触发「新建」路径）
const page = (await api('GET', `/api/notes/${source.id}/blocks`)).body
const sourceText = `见 [[${TARGET}]] 和 #${TAG}`
await api('PATCH', `/api/notes/${source.id}/blocks/${page.blocks[0].block.id}/content`, {
  contentMd: sourceText,
})

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
win.on('pageerror', (e) => errors.push(String(e)))
win.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

await win.goto(`${APP}/#/notes/${source.id}`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2800)

// 卡片预览里应该渲染出 wiki 链接与标签
const link = win.locator('article.card .md-wikilink', { hasText: TARGET }).first()
check('卡片正文渲染出双链', (await link.count()) === 1, `${await link.count()} 个`)

// 点它 → 应该跳到目标笔记
await link.click()
await win.waitForTimeout(2200)
check('点击卡片里的双链会打开目标笔记', win.url().includes(target.id), win.url().split('#')[1] ?? '')
check('打开的是正确那篇', ((await win.locator('.note-head .title, h2.title').first().innerText().catch(() => '')) ?? '').includes(TARGET),
  ((await win.locator('.note-head .title, h2.title').first().innerText().catch(() => '')) ?? '').trim())

// 回到来源笔记，点标签 → 应该按标签筛选
await win.goto(`${APP}/#/notes/${source.id}`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2600)
const tagLink = win.locator('article.card .md-wikilink[data-tag]').first()
check('卡片正文渲染出标签链接', (await tagLink.count()) === 1)
await tagLink.click()
await win.waitForTimeout(2000)
const sidebarActive = ((await win.locator('.sidebar .tag.active').first().innerText().catch(() => '')) ?? '').trim()
check('点击卡片里的标签会按它筛选', sidebarActive.includes(TAG), sidebarActive || '（侧栏没有选中的标签）')

// 指向不存在笔记的双链 → 应当当场建出并打开。
// 关键：内容在**打开浏览器之前**就写好。上一次我把 PATCH 放在页面已经打开之后，
// 结果被应用自身的自动保存回写覆盖，测出来的是"内容没变"，不是功能本身。
const stubSource = (await api('POST', '/api/notes', { title: `不存在链接来源${RUN}`, contentMd: '', tags: [] })).body
created.push(stubSource.id)
const stubPage = (await api('GET', `/api/notes/${stubSource.id}/blocks`)).body
await api('PATCH', `/api/notes/${stubSource.id}/blocks/${stubPage.blocks[0].block.id}/content`, {
  contentMd: `指向不存在 [[尚未存在${RUN}]]`,
})
await win.goto(`${APP}/#/notes/${stubSource.id}`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2600)
const freshLink = win.locator('article.card .md-wikilink').first()
const freshText = ((await freshLink.innerText().catch(() => '')) ?? '').trim()
check('卡片渲染出指向不存在笔记的双链', freshText.includes(`尚未存在${RUN}`), freshText || '（读不到）')
await freshLink.click()
await win.waitForTimeout(2200)
// 按标题找（列表首行可能是刚被 touch 的来源笔记，不能靠顺序）
const all = (await api('GET', '/api/notes?limit=50')).body ?? []
const fresh = all.find((n) => n.title.includes(`尚未存在${RUN}`))
check('点击不存在的双链会建出它', !!fresh, fresh ? fresh.title : all.map((n) => n.title).join(' | ').slice(0, 60))
if (fresh) {
  created.push(fresh.id)
  check('并打开它', win.url().includes(fresh.id), win.url().split('#')[1] ?? '')
}

// 外链：必须新窗口打开 —— 单页应用被原地导航走等于把应用顶掉
const extSource = (await api('POST', '/api/notes', { title: `外链来源${RUN}`, contentMd: '', tags: [] })).body
created.push(extSource.id)
const extPage = (await api('GET', `/api/notes/${extSource.id}/blocks`)).body
await api('PATCH', `/api/notes/${extSource.id}/blocks/${extPage.blocks[0].block.id}/content`, {
  contentMd: '外链请看 [示例站点](https://example.com/)',
})
await win.goto(`${APP}/#/notes/${extSource.id}`, { waitUntil: 'networkidle' })
await win.waitForTimeout(2600)
const extLink = win.locator('article.card a[href^="https://"]').first()
check('卡片渲染出外链', (await extLink.count()) === 1, `${await extLink.count()} 个`)
check('外链带 target=_blank', (await extLink.getAttribute('target')) === '_blank',
  String(await extLink.getAttribute('target')))
const [popup] = await Promise.all([
  win.context().waitForEvent('page', { timeout: 8000 }).catch(() => null),
  extLink.click(),
])
await win.waitForTimeout(1200)
check('外链在新窗口打开', !!popup, popup ? popup.url() : '（没有新窗口）')
check('应用本身没被导航走', win.url().includes(extSource.id), win.url().split('#')[1] ?? '')
if (popup) await popup.close()

check('全程无运行时错误', errors.length === 0, errors.slice(0, 2).join(' | ') || '无')

await browser.close()
for (const id of created.reverse()) await api('DELETE', `/api/notes/${id}?hard=true`)
console.log(`\n检查: ${passed}/${passed + failed} 通过`)
if (failed) process.exitCode = 1
