/**
 * 卡片工具行与视图模式 + 笔记列表常驻。
 *
 * 盯住三件曾经坏掉/缺失的事：
 *  1. 打开笔记后，笔记列表不能消失（否则要回到上一篇得绕侧栏）；
 *  2. 卡片右上角的 "＋ 标题" 与十字拖动把手不能重叠（两者曾都在 top:4px/right:6px）；
 *  3. 每张卡片独立的 源码 / 双列 / 预览 三种显示模式。
 *
 * 用法: node tools/card_modes_test.mjs
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

// 工具行元素计数包含记忆曲线的卡片徽标，所以先确保该插件处于启用状态
// （插件被停用时徽标会被界面侧隐藏，元素数就少一个 —— 那是另一个测试的情形）。
await api('PUT', '/api/plugins/memory-curve/active?value=true').catch(() => undefined)

async function newNote(title, contentMd = '') {
  const note = await api('POST', '/api/notes', { title: `${title}-${RUN}`, contentMd: '', tags: [] })
  created.push(note.id)
  const page = `/api/notes/${note.id}/blocks`
  if (contentMd) {
    const p = await api('GET', page)
    await api('PATCH', `${page}/blocks/${p.blocks[0].block.id}/content`, { contentMd })
  }
  return { id: note.id, page, title: `${title}-${RUN}` }
}

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)

try {
  const errors = []

  // ---------------------------------------------------------------- 1. 列表常驻
  console.log('=== 打开笔记后列表不应消失 ===')
  {
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    win.on('pageerror', (e) => errors.push(String(e)))
    win.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text())
    })
    await win.goto(APP + '/', { waitUntil: 'networkidle' })
    await win.waitForTimeout(2000)
    const rowsBefore = await win.locator('.note-row').count()
    check('打开前列表有笔记', rowsBefore > 0, `${rowsBefore} 篇`)

    await win.locator('.note-row').first().click()
    await win.waitForSelector('.page-surface', { timeout: 15000 })
    await win.waitForTimeout(2000)

    const after = await win.evaluate(() => {
      const list = document.querySelector('.note-list')
      const page = document.querySelector('.page-column')
      const r = (el) => (el ? Math.round(el.getBoundingClientRect().width) : 0)
      return {
        rows: document.querySelectorAll('.note-row').length,
        listWidth: r(list),
        pageWidth: r(page),
        hasPage: !!document.querySelector('.page-surface'),
      }
    })
    check('打开笔记后列表仍然可见', after.rows > 0 && after.listWidth > 0,
      `row=${after.rows} list=${after.listWidth}px`)
    check('笔记页同时显示', after.hasPage, `page=${after.pageWidth}px`)
    check('列表与笔记页并排（都占宽度）', after.listWidth > 100 && after.pageWidth > 100,
      `${after.listWidth} + ${after.pageWidth}`)

    // The note's own header must live in the right-hand column, beside the list, not across
    // the top of it.
    const head = await win.evaluate(() => {
      const h = document.querySelector('.note-head')
      const list = document.querySelector('.note-list')
      const col = document.querySelector('.page-column')
      if (!h || !list || !col) return null
      const hb = h.getBoundingClientRect()
      const lb = list.getBoundingClientRect()
      const cb = col.getBoundingClientRect()
      return {
        insideColumn: col.contains(h),
        startsAtOrRightOfList: hb.left >= lb.right - 1,
        alignedWithColumn: Math.abs(hb.left - cb.left) <= 1,
      }
    })
    check('笔记标题在右侧笔记列内（不再横跨列表上方）',
      !!head && head.insideColumn && head.startsAtOrRightOfList && head.alignedWithColumn,
      JSON.stringify(head))

    const chrome = await win.evaluate(() => ({
      // Only the note header's own controls matter here; the context menu has its own
      // "新建卡片（或双击页面空白处）" entry with a different label.
      addCardButton: [...document.querySelectorAll('.note-head button')].filter((b) =>
        b.textContent.replace(/\s+/g, '').includes('＋卡片'),
      ).length,
      // The running count is deliberately absent; only the cap warning should ever appear.
      limitText: document.querySelector('.head-meta .limit-full')?.textContent.trim() ?? null,
      cardCountShown: /\d+\s*\/\s*\d+\s*张卡片/.test(document.querySelector('.note-head')?.textContent ?? ''),
    }))
    check('已移除 ＋ 卡片 按钮（用双击创建）', chrome.addCardButton === 0, `${chrome.addCardButton} 个`)
    check('未达上限时不显示卡片计数', !chrome.cardCountShown && chrome.limitText === null,
      `limitText=${JSON.stringify(chrome.limitText)}`)

    // Switching notes from the list must still work while a note is open.
    const second = await win.locator('.note-row').nth(1)
    if (await second.count()) {
      const before = await win.evaluate(() => location.hash)
      await second.click()
      await win.waitForTimeout(2500)
      const now = await win.evaluate(() => location.hash)
      check('列表里能直接切换笔记', now !== before && !!now, `${before} → ${now}`)
    }
    await win.close()
  }

  // ---------------------------------------------------------------- 2 & 3. 工具行与模式
  console.log('\n=== 卡片工具行：不重叠 + 三种视图模式 ===')
  {
    const note = await newNote('模式测试', '# 标题\n\n这是**粗体**。\n\n- 甲\n- 乙')
    const win = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    win.on('pageerror', (e) => errors.push(String(e)))
    win.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text())
    })
    await win.goto(APP + '/', { waitUntil: 'networkidle' })
    await win.waitForTimeout(1500)
    await win.locator('.note-row', { hasText: note.title }).first().click()
    await win.waitForSelector('.page-surface', { timeout: 15000 })
    await win.waitForTimeout(2000)

    // Select the card so every affordance is pinned visible at once.
    await win.locator('article.card').first().click()
    await win.waitForTimeout(600)

    const overlap = await win.evaluate(() => {
      const card = document.querySelector('article.card')
      const tools = card.querySelector('.card-tools')
      const els = [...(tools?.querySelectorAll('.mode, .collapse, .mc-chip') ?? [])]
      const hits = []
      for (let i = 0; i < els.length; i++) {
        for (let j = i + 1; j < els.length; j++) {
          const a = els[i].getBoundingClientRect()
          const b = els[j].getBoundingClientRect()
          const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left)
          const oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
          if (ox > 0 && oy > 0) {
            hits.push(`${els[i].className.split(' ').pop()}×${els[j].className.split(' ').pop()}`)
          }
        }
      }
      const cardBox = card.getBoundingClientRect()
      const out = els.filter((el) => {
        const b = el.getBoundingClientRect()
        return b.left < cardBox.left || b.right > cardBox.right + 1
      }).length
      const body = card.querySelector('.body.read')
      const bodyBox = body?.getBoundingClientRect()
      return {
        count: els.length,
        hits,
        out,
        cardTitleInputs: card.querySelectorAll('input.title-input, .head .title').length,
        grip: card.querySelectorAll('.grip, button.grip').length,
        addTitle: card.querySelectorAll('.add-title').length,
        headStrips: card.querySelectorAll('.head, .head-hint').length,
        // The tools are hidden overlay controls: they must not reserve layout space.
        toolsOpacity: tools ? getComputedStyle(tools).opacity : null,
        bodyTopGap: bodyBox ? Math.round(bodyBox.top - cardBox.top) : null,
      }
    })
    check('工具行元素齐全（3 个模式按钮 + 折叠按钮 + 记忆曲线徽标）', overlap.count === 5, `${overlap.count} 个`)
    check('工具行元素互不重叠', overlap.hits.length === 0, overlap.hits.join(', ') || '无重叠')
    check('工具行都在卡片范围内', overlap.out === 0, `${overlap.out} 个溢出`)
    check('卡片内已无标题框（标题来自内容的 ## ）', overlap.cardTitleInputs === 0,
      `${overlap.cardTitleInputs} 个标题元素`)
    check('已移除十字拖动把手', overlap.grip === 0)
    check('已移除 ＋ 标题 按钮', overlap.addTitle === 0)
    check('已移除顶部拖动条（不再占一条带子）', overlap.headStrips === 0,
      `${overlap.headStrips} 个`)
    check('内容贴近卡片顶部（无过大间隔）',
      overlap.bodyTopGap !== null && overlap.bodyTopGap <= 12, `间隔 ${overlap.bodyTopGap}px`)

    // 模式按钮/折叠按钮是隐藏式覆盖层；记忆曲线徽标在启用后必须**常驻**可见——
    // 这就是开启与未开启肉眼可见的差异。透明度检查针对 .modes（容器本身不再淡入淡出）。
    const toolsOpacity = (sel) =>
      win.evaluate((s) => {
        const el = document.querySelector(s)
        return el ? Number(getComputedStyle(el).opacity) : null
      }, sel)
    const shownModes = await toolsOpacity('article.card .modes')
    // Deselect by pressing blank page, then move the pointer off the card entirely: only then is
    // the idle state actually testable (a selected card keeps its tools pinned).
    const idleSpot = await win.evaluate(() => {
      const s = document.querySelector('.page-surface').getBoundingClientRect()
      const boxes = [...document.querySelectorAll('article.card')].map((b) => b.getBoundingClientRect())
      for (let y = s.bottom - 20; y > s.y + 20; y -= 20) {
        for (let x = s.x + 20; x < s.right - 20; x += 20) {
          if (boxes.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)) continue
          if (document.elementFromPoint(x, y)?.classList.contains('page-surface')) return { x, y }
        }
      }
      return null
    })
    if (idleSpot) await win.mouse.click(idleSpot.x, idleSpot.y)
    await win.mouse.move(5, 400)
    await win.waitForTimeout(600)
    const hiddenModes = await toolsOpacity('article.card .modes')
    check('模式/折叠按钮为隐藏式（选中/悬停才出现）',
      shownModes === 1 && hiddenModes === 0,
      `selected=${shownModes} idle=${hiddenModes}`)
    const idleChip = await toolsOpacity('article.card .mc-chip.mc-idle-chip')
    check('未开启时 🧠 占位随工具行淡入', idleChip === 0, `idle=${idleChip}`)

    const state = () =>
      win.evaluate(() => {
        const card = document.querySelector('article.card')
        const ta = card.querySelector('textarea')
        const md = card.querySelector('.md-preview')
        const r = (el) => (el ? el.getBoundingClientRect() : null)
        return {
          hasTextarea: !!ta,
          textareaValue: ta ? ta.value : null,
          hasPreview: !!md,
          previewHtml: md ? md.innerHTML : null,
          previewText: md ? md.textContent.replace(/\s+/g, ' ').trim() : null,
          split: !!card.querySelector('.body.split'),
          active: [...card.querySelectorAll('.mode')].find((b) => b.getAttribute('aria-pressed') === 'true')?.textContent.trim(),
          taBox: r(ta),
          mdBox: r(md),
        }
      })
    const clickMode = async (glyph) => {
      await win.locator('article.card .mode', { hasText: glyph }).first().click()
      await win.waitForTimeout(1200)
    }

    let s = await state()
    check('默认是预览模式（渲染而非源码）', !s.hasTextarea && s.hasPreview && s.active === '¶',
      `active=${s.active}`)
    check('预览渲染成 HTML', !!s.previewHtml && s.previewHtml.includes('<h1>'),
      (s.previewHtml ?? '').slice(0, 40))

    await clickMode('≡')
    s = await state()
    check('源码模式：显示可编辑源码', s.hasTextarea && !s.hasPreview && s.active === '≡',
      `active=${s.active}`)
    check('源码模式里能看到 Markdown 原文', (s.textareaValue ?? '').includes('# 标题'),
      JSON.stringify((s.textareaValue ?? '').slice(0, 20)))

    await clickMode('◫')
    s = await state()
    check('双列模式：源码与预览同时在', s.hasTextarea && s.hasPreview && s.split && s.active === '◫',
      `active=${s.active}`)
    check('双列是左右并排',
      !!s.taBox && !!s.mdBox && s.taBox.right <= s.mdBox.left + 1,
      s.taBox && s.mdBox ? `ta.right=${Math.round(s.taBox.right)} preview.left=${Math.round(s.mdBox.left)}` : '缺元素')

    // Live preview: type in the textarea, the right pane must follow.
    await win.locator('article.card textarea').first().click()
    await win.keyboard.press('Control+a')
    await win.keyboard.type('## 实时预览测试\n\n右边应该跟着变。', { delay: 5 })
    await win.waitForTimeout(6000)
    s = await state()
    check('双列预览跟随输入实时更新', (s.previewText ?? '').includes('实时预览测试'),
      JSON.stringify((s.previewText ?? '').slice(0, 30)))

    await clickMode('¶')
    s = await state()
    check('预览模式：无输入框、只渲染', !s.hasTextarea && s.hasPreview && s.active === '¶',
      `active=${s.active}`)

    // Content written in source mode must have been saved.
    const saved = (await api('GET', note.page)).blocks[0].block.contentMd
    check('源码模式下写的内容已保存到卡片', saved.includes('实时预览测试'),
      JSON.stringify(saved.slice(0, 24)))

    // Leaving a card returns it to the preview, unless the user chose a mode themselves.
    const blankPoint = () =>
      win.evaluate(() => {
        const s = document.querySelector('.page-surface').getBoundingClientRect()
        const boxes = [...document.querySelectorAll('article.card')].map((b) => b.getBoundingClientRect())
        for (let y = s.y + 20; y < s.y + s.height - 20; y += 20) {
          for (let x = s.x + 20; x < s.x + s.width - 20; x += 20) {
            if (boxes.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)) continue
            if (document.elementFromPoint(x, y)?.classList.contains('page-surface')) return { x, y }
          }
        }
        return null
      })

    // Just write (no mode button pressed) then leave: must fall back to preview.
    const cardBox = await win.locator('article.card').first().boundingBox()
    await win.mouse.dblclick(cardBox.x + cardBox.width / 2, cardBox.y + 60)
    await win.waitForTimeout(1200)
    s = await state()
    check('双击进入编写会切到源码', s.hasTextarea && s.active === '≡', `active=${s.active}`)
    await win.keyboard.type('随手写的。', { delay: 6 })
    await win.waitForTimeout(400)
    const away = await blankPoint()
    await win.mouse.click(away.x, away.y)
    await win.waitForTimeout(3000)
    s = await state()
    check('未选过模式：离开后自动回到预览',
      !s.hasTextarea && s.hasPreview && s.active === '¶', `active=${s.active}`)

    // Choose split explicitly, then leave: the choice must stand.
    await clickMode('◫')
    s = await state()
    check('显式选双列后进入编写', s.hasTextarea && s.active === '◫', `active=${s.active}`)
    await win.locator('article.card textarea').first().click()
    await win.keyboard.type('双列里写的。', { delay: 6 })
    await win.waitForTimeout(400)
    await win.mouse.click(away.x, away.y)
    // 离开焦点要先写库再重新渲染，固定 sleep 在负载下会假红（实测偶发过一次），所以轮询到状态落定。
    let settled = await state()
    for (let i = 0; i < 14 && !(settled.hasTextarea && settled.active === '◫'); i++) {
      await win.waitForTimeout(500)
      settled = await state()
    }
    check('显式选过双列：离开后仍保持双列',
      settled.hasTextarea && settled.active === '◫', `active=${settled.active}`)

    // Modes are per card: a second card keeps its own.
    const p = await api('GET', note.page)
    await api('POST', `${note.page}/blocks`, { x: 0, y: 700, w: 500, h: 240, contentMd: '# 第二张' })
    await win.reload({ waitUntil: 'networkidle' })
    await win.waitForTimeout(3000)
    const cards = win.locator('article.card')
    if ((await cards.count()) >= 2) {
      const modesOf = () =>
        win.evaluate(() =>
          [...document.querySelectorAll('article.card')].map(
            (c) => [...c.querySelectorAll('.mode')]
              .find((b) => b.getAttribute('aria-pressed') === 'true')?.textContent.trim() ?? null,
          ),
        )
      const before = await modesOf()
      await cards.nth(1).click()
      await win.waitForTimeout(800)
      await win.locator('article.card').nth(1).locator('.mode', { hasText: '≡' }).first().click()
      await win.waitForTimeout(1500)
      const after = await modesOf()
      check('模式是按卡片独立的（第二张改源码不影响第一张）',
        after[0] === before[0] && after[1] === '≡', JSON.stringify({ before, after }))
    }
    await win.close()
  }

  check('没有运行时错误', errors.length === 0, errors.slice(0, 2).join(' | '))
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
