/**
 * 写卡片 → 离开焦点 → 内容必须保存下来。
 *
 * 这里盯的是一个真实发生过的数据丢失：卡片里的文字还没提交，用户就点了别处，
 * 结果卡片被当成"空卡片"删掉，随后到达的保存请求打成 404，文字彻底消失。
 *
 * 根因是事件的先后顺序 —— 离开卡片的 pointerdown 比 textarea 的 blur 先到，
 * 于是"最近一次保存"表里还是空的，判定依据只剩数据库里的旧内容（空）。
 *
 * 用法: node tools/card_save_test.mjs [baseUrl]
 */
import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const API = process.env.THISNODE_API ?? 'http://127.0.0.1:45917'
const APP = process.env.THISNODE_APP ?? 'http://localhost:5173'

const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

let passed = 0
let failed = 0
function check(label, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  PASS  ${label}${detail ? '  ' + detail : ''}`)
  } else {
    failed++
    console.log(`  FAIL  ${label}${detail ? '  ' + detail : ''}`)
  }
}

async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

const RUN = Date.now().toString().slice(-6)
const created = []

async function newNote(title) {
  const note = await api('POST', '/api/notes', { title: `${title}-${RUN}`, contentMd: '' })
  created.push(note.id)
  return { id: note.id, page: `/api/notes/${note.id}/blocks` }
}

async function openNote(win, title) {
  await win.goto(APP + '/', { waitUntil: 'networkidle' })
  await win.waitForTimeout(1500)
  await win.locator('.note-row', { hasText: `${title}-${RUN}` }).first().click()
  await win.waitForSelector('.page-surface', { timeout: 15000 })
  await win.waitForTimeout(2000)
}

/** A blank point on the page that is not covered by any card. */
const blankPoint = (win) =>
  win.evaluate(() => {
    const s = document.querySelector('.page-surface').getBoundingClientRect()
    const boxes = [...document.querySelectorAll('article.card')].map((b) => b.getBoundingClientRect())
    for (let y = s.y + 16; y < s.y + s.height - 16; y += 16) {
      for (let x = s.x + 16; x < s.x + s.width - 16; x += 16) {
        if (boxes.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)) continue
        if (document.elementFromPoint(x, y)?.classList.contains('page-surface')) {
          return { x: Math.round(x), y: Math.round(y) }
        }
      }
    }
    return null
  })

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)

try {
  console.log('=== 离开焦点必须保存卡片内容 ===')

  // ---- 1. 空白笔记里新建卡片，写字，点空白处离开 ----
  const one = await newNote('保存测试A')
  {
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    const errors = []
    win.on('pageerror', (e) => errors.push(String(e)))
    win.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text())
    })
    await openNote(win, '保存测试A')

    const spot = await blankPoint(win)
    check('空白页有可双击的空白处', !!spot)
    await win.mouse.dblclick(spot.x, spot.y)
    await win.waitForTimeout(1200)
    const marker = `新建卡片写的字-${RUN}`
    await win.keyboard.type(marker, { delay: 10 })
    await win.waitForTimeout(400)

    const away = await blankPoint(win)
    await win.mouse.click(away.x, away.y)
    await win.waitForTimeout(3000)

    const page = await api('GET', one.page)
    check('点空白处离开后内容已保存',
      page.blocks.some((v) => (v.contentMd ?? '').includes(marker)),
      JSON.stringify(page.blocks.map((v) => v.contentMd)))
    check('卡片没有被误删为"空卡片"', page.blocks.length >= 1, `cards=${page.blocks.length}`)
    check('离开后退出编辑态',
      (await win.evaluate(() => document.querySelectorAll('article.card.editing').length)) === 0)
    check('没有 404 / 运行时错误', errors.length === 0, errors.slice(0, 2).join(' | '))
    await win.close()
  }

  // ---- 2. 已有卡片里改字，点空白处离开 ----
  const two = await newNote('保存测试B')
  {
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await openNote(win, '保存测试B')
    const card = await win.locator('article.card').first().boundingBox()
    await win.mouse.dblclick(card.x + card.width / 2, card.y + 40)
    await win.waitForTimeout(1000)
    const marker = `改过的内容-${RUN}`
    await win.keyboard.type(marker, { delay: 10 })
    await win.waitForTimeout(400)
    const away = await blankPoint(win)
    await win.mouse.click(away.x, away.y)
    await win.waitForTimeout(3000)

    const page = await api('GET', two.page)
    check('已有卡片离开焦点后内容已保存',
      page.blocks.some((v) => (v.contentMd ?? '').includes(marker)),
      JSON.stringify(page.blocks.map((v) => v.contentMd)))
    await win.close()
  }

  // ---- 3. 真的空卡片仍然应被自动清理（别把修复做成不再回收） ----
  const three = await newNote('保存测试C')
  {
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await openNote(win, '保存测试C')
    const spot = await blankPoint(win)
    await win.mouse.dblclick(spot.x, spot.y)
    await win.waitForTimeout(1200)
    const before = (await api('GET', three.page)).blocks.length
    const away = await blankPoint(win)
    await win.mouse.click(away.x, away.y)
    await win.waitForTimeout(3000)
    const after = (await api('GET', three.page)).blocks.length
    check('始终没写字的空卡片仍会被自动删除', after === before - 1, `${before} → ${after}`)
    await win.close()
  }

  // ---- 4. 写很多字：卡片应向下伸展，宽度不变 ----
  const four = await newNote('伸展测试D')
  {
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await openNote(win, '伸展测试D')
    const card = await win.locator('article.card').first().boundingBox()
    await win.mouse.dblclick(card.x + card.width / 2, card.y + 40)
    await win.waitForTimeout(1000)
    const geom = () =>
      win.evaluate(() => {
        const el = document.querySelector('article.card')
        const r = el.getBoundingClientRect()
        const ta = el.querySelector('textarea.body.edit')
        return { w: Math.round(r.width), h: Math.round(r.height), taW: ta ? Math.round(ta.getBoundingClientRect().width) : null }
      })
    const before = await geom()
    for (let i = 0; i < 22; i++) {
      await win.keyboard.type(`第 ${i + 1} 行文字，用来把卡片撑高，宽度不应该变。`, { delay: 2 })
      await win.keyboard.press('Enter')
    }
    await win.waitForTimeout(1500)
    const after = await geom()
    const server = (await api('GET', four.page)).blocks[0].block

    check('写满后卡片向下伸展', after.h > before.h + 100, `${before.h} → ${after.h}`)
    check('伸展时宽度不变（水平不动）', after.w === before.w, `${before.w} → ${after.w}`)
    check('textarea 不超出卡片宽度', after.taW !== null && after.taW <= after.w, `ta=${after.taW} card=${after.w}`)
    check('伸展不会失控（高度远小于文字行数×行距上限）', after.h < 3000, `h=${after.h}`)
    check('高度已写回服务端（刷新后仍是伸展后的高度）', server.h === after.h,
      `server=${server.h} dom=${after.h}`)
    await win.close()
  }
} finally {
  for (const id of created) {
    await api('DELETE', `/api/notes/${id}?hard=true`).catch(() => undefined)
  }
  await browser.close()
}

console.log(`\n${'='.repeat(60)}`)
console.log(`检查: ${passed}/${passed + failed} 通过`)
if (failed) {
  console.log('失败项:')
  process.exitCode = 1
}
